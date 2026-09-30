# PadPort RGSS compatibility layer (GPL-3.0-or-later, part of PadPort).
#
# Runs as the first mkxp-z preload script: after $RGSS_SCRIPTS was decoded,
# before any game script is evaluated. It never touches game files; only the
# in-memory script sources of sections that do not compile on the bundled
# Ruby get a conservative Ruby 1.8 -> modern syntax rewrite.

require "ripper"

module PadPort
  module_function

  def log(message)
    System.puts("[PadPort] #{message}")
  rescue StandardError
    nil
  end

  # ---- Ruby 1.8 syntax rewriting ------------------------------------------

  def syntax_error(source, name)
    RubyVM::InstructionSequence.compile(source, name)
    nil
  rescue SyntaxError => e
    e
  end

  class Lexer < Ripper
    attr_reader :tokens

    def initialize(source)
      super(source, "-", 1)
      @tokens = []
    end

    SCANNER_EVENT_TABLE.each_key do |event|
      define_method("on_#{event}") do |token|
        @tokens << [lineno, column, event, token, state]
        token
      end
    end

    PARSER_EVENT_TABLE.each_key do |event|
      define_method("on_#{event}") { |*args| args[0] }
    end

    def compile_error(*) end
    def warn(*) end
    def warning(*) end
  end

  SKIP = [:sp, :nl, :ignored_nl, :comment, :embdoc_beg, :embdoc, :embdoc_end,
          :heredoc_end, :ignored_sp, :words_sep].freeze
  OPEN = [:lbrace, :lparen, :lbracket, :embexpr_beg, :tlambeg, :tlambda].freeze
  CLOSE = [:rbrace, :rparen, :rbracket, :embexpr_end].freeze
  BLOCK_KEYWORDS = %w[do def begin case class module if unless while until for].freeze
  EXPR_LABEL = defined?(Ripper::EXPR_LABEL) ? Ripper::EXPR_LABEL : 1024

  def tokens(source)
    lexer = Lexer.new(source)
    lexer.parse
    starts = [0]
    source.b.each_line { |line| starts << starts[-1] + line.bytesize }
    lexer.tokens.map do |line, column, event, text, state|
      [starts[line - 1] + column, event, text, state]
    end.sort_by(&:first)
  end

  # {a, b, c, d} (Ruby 1.8 hash list) -> {a => b, c => d}
  def hash_list_edits(toks)
    edits = []
    code = toks.reject { |t| SKIP.include?(t[1]) }
    code.each_with_index do |tok, index|
      next unless tok[1] == :lbrace && (tok[3].to_i & EXPR_LABEL) != 0
      depth = 0
      commas = []
      elements = 0
      current = 0
      valid = true
      j = index + 1
      while j < code.size
        t = code[j]
        if OPEN.include?(t[1])
          depth += 1
        elsif CLOSE.include?(t[1])
          break if depth.zero?
          depth -= 1
        elsif depth.zero?
          case t[1]
          when :comma
            commas << t
            elements += 1
            current = 0
            j += 1
            next
          when :label, :label_end
            valid = false
          when :op
            assignment = t[2] == "=>" || t[2] == "=" || (t[2].end_with?("=") && !%w[== != >= <= === =~].include?(t[2]))
            splat = current.zero? && %w[* ** &].include?(t[2])
            valid = false if assignment || splat
          when :kw
            valid = false if BLOCK_KEYWORDS.include?(t[2])
          end
        end
        current += 1
        j += 1
      end
      break_ok = j < code.size && code[j][1] == :rbrace
      elements += 1 if current > 0
      next unless valid && break_ok && !commas.empty? && elements.even?
      commas.each_with_index do |comma, n|
        edits << [comma[0], 1, " =>"] if n.even? && n < elements - 1
      end
    end
    edits
  end

  # `when x: foo` (Ruby 1.8) -> `when x then foo`
  def when_colon_edits(toks)
    edits = []
    in_when = false
    depth = 0
    toks.each do |pos, event, text, _state|
      if event == :kw && text == "when"
        in_when = true
        depth = 0
      elsif in_when
        if OPEN.include?(event) then depth += 1
        elsif CLOSE.include?(event) then depth -= 1
        elsif event == :nl || event == :semicolon || (event == :kw && text == "then")
          in_when = false
        elsif depth.zero? && event == :op && text == ":"
          edits << [pos, 1, " then"]
          in_when = false
        elsif depth.zero? && event == :label_end && text.end_with?(":")
          edits << [pos + text.bytesize - 1, 1, " then"]
          in_when = false
        end
      end
    end
    edits
  end

  # `foo (a, b)` (Ruby 1.8 call with a space) -> `foo(a, b)`. Only when the
  # parentheses contain a top-level comma, so they cannot be a grouped
  # expression like `puts (1 + 2) * 3`.
  CALLABLE = [:ident, :const, :fid].freeze
  # chained_only: nil = only calls with several arguments (syntax errors);
  # false = those plus chained calls; true = chained calls only.
  def spaced_call_edits(toks, chained_only = nil)
    edits = []
    toks.each_with_index do |tok, index|
      next unless tok[1] == :sp && index > 0 && index + 1 < toks.size
      previous, following = toks[index - 1], toks[index + 1]
      next unless following[1] == :lparen
      callable = CALLABLE.include?(previous[1]) || (previous[1] == :kw && %w[super yield].include?(previous[2]))
      next unless callable
      next unless top_level_comma?(toks, index + 1) || (!chained_only.nil? && chained?(toks, index + 1))
      next if chained_only && !chained?(toks, index + 1)
      edits << [tok[0], tok[2].bytesize, ""]
    end
    edits
  end

  # Ruby 1.8 read `foo (x).bar` as `foo(x).bar`; Ruby 1.9+ reads `foo((x).bar)`.
  # Such sections still compile, so this runs for every RGSS1/RGSS2 section.
  def chained_call_edits(toks)
    spaced_call_edits(toks, true)
  end

  def chained?(toks, open_index)
    depth = 0
    index = open_index + 1
    while index < toks.size
      t = toks[index]
      if OPEN.include?(t[1]) then depth += 1
      elsif CLOSE.include?(t[1])
        if depth.zero?
          following = toks[index + 1]
          return false unless following
          return following[1] == :period || following[1] == :lbracket ||
                 (following[1] == :op && %w[&. ::].include?(following[2]))
        end
        depth -= 1
      end
      index += 1
    end
    false
  end

  def top_level_comma?(toks, open_index)
    depth = 0
    toks[(open_index + 1)..-1].each do |t|
      if OPEN.include?(t[1]) then depth += 1
      elsif CLOSE.include?(t[1])
        return false if depth.zero?
        depth -= 1
      elsif depth.zero? && t[1] == :comma then return true
      end
    end
    false
  end

  def apply(source, edits)
    bytes = source.b
    edits.sort_by { |e| -e[0] }.each do |pos, length, replacement|
      bytes[pos, length] = replacement.b
    end
    bytes.force_encoding(source.encoding)
  end

  def rewrite(source)
    toks = tokens(source)
    calls = spaced_call_edits(toks, rgss_version >= 3 ? nil : false)
    apply(source, hash_list_edits(toks) + when_colon_edits(toks) + calls)
  end

  SPACED_CALL = /[\w?!] +\(/.freeze

  # Second pass for Ruby 1.8 games: sections that compile but would call
  # methods on the argument instead of on the result.
  def patch_chained_calls
    return 0 if rgss_version >= 3
    count = 0
    $RGSS_SCRIPTS.each_with_index do |script, index|
      next unless script.is_a?(Array) && script[3].is_a?(String) && script[3] =~ SPACED_CALL
      begin
        edits = chained_call_edits(tokens(script[3]))
        next if edits.empty?
        candidate = apply(script[3], edits)
        next if syntax_error(candidate, "#{index}:#{script[1]}")
        script[3] = candidate
        count += 1
      rescue StandardError => e
        log("section #{index} chained-call pass skipped: #{e.class}: #{e.message}")
      end
    end
    count
  end

  def patch_scripts
    return unless $RGSS_SCRIPTS.is_a?(Array)
    fixed = 0
    failed = []
    $RGSS_SCRIPTS.each_with_index do |script, index|
      next unless script.is_a?(Array) && script[3].is_a?(String)
      name = "#{index}:#{script[1]}"
      next unless syntax_error(script[3], name)
      # Parser error recovery can hide later tokens, so repeat until the
      # section compiles or no further rewrite is possible.
      candidate = script[3]
      error = nil
      8.times do
        begin
          rewritten = rewrite(candidate)
          error = syntax_error(rewritten, name)
        rescue StandardError, SyntaxError => e
          error = e
          break
        end
        break if rewritten == candidate
        candidate = rewritten
        break unless error
      end
      if error
        failed << name
        log("section #{name} still does not compile: #{error.message.lines.first.to_s.strip}")
      else
        script[3] = candidate
        fixed += 1
      end
    end
    chained = patch_chained_calls
    log("Ruby 1.8 compatibility: #{fixed} section(s) rewritten, #{failed.size} unresolved, #{chained} with `foo (x).bar` calls")
  end

  # ---- Windows environment expected by RPG Maker games ----------------------

  def save_root
    root = ENV["PADPORT_SAVE_DIR"].to_s
    root = System.data_directory.to_s if root.empty?
    root = root.chomp("/")
    Dir.mkdir(root) unless File.directory?(root)
    root
  end

  def install_environment
    root = save_root
    %w[APPDATA LOCALAPPDATA AV_APPDATA USERPROFILE HOME TEMP TMP].each do |name|
      ENV[name] = root if ENV[name].to_s.empty?
    end
    ENV["USERNAME"] = "Player" if ENV["USERNAME"].to_s.empty?
  end

  # Game scripts build Windows paths ("#{appdata}\\Game\\Save1.rxdata").
  # Ruby's File/Dir on Android would treat backslashes as file name
  # characters, so path arguments are normalised to forward slashes.
  def win_path(value)
    value.is_a?(String) && value.include?("\\") ? value.tr("\\", "/") : value
  end

  PATH_METHODS = {
    File => %i[open new exist? exists? file? directory? readable? writable? size size? zero? delete unlink
               rename read readlines binread write binwrite foreach mtime atime ctime stat lstat expand_path
               basename dirname extname join utime chmod],
    Dir => %i[mkdir rmdir delete unlink exist? exists? entries foreach children each_child chdir glob [] open new],
    FileTest => %i[exist? exists? file? directory? readable? writable? size size? zero?],
    IO => %i[read readlines binread write binwrite foreach],
  }.freeze

  def install_windows_paths
    File.singleton_class.send(:alias_method, :exists?, :exist?) unless File.respond_to?(:exists?)
    Dir.singleton_class.send(:alias_method, :exists?, :exist?) unless Dir.respond_to?(:exists?)
    PATH_METHODS.each do |owner, names|
      patch = Module.new do
        names.each do |name|
          next unless owner.respond_to?(name)
          define_method(name) do |*args, **options, &block|
            args = args.map { |arg| PadPort.win_path(arg) }
            options.empty? ? super(*args, &block) : super(*args, **options, &block)
          end
        end
      end
      owner.singleton_class.prepend(patch)
    end
    kernel = Module.new do
      names = %i[open load_data save_data]
      names.each do |name|
        define_method(name) do |*args, &block|
          super(*args.each_with_index.map { |arg, i| i.zero? ? PadPort.win_path(arg) : arg }, &block)
        end
      end
      private(*names)
    end
    Object.prepend(kernel)
  end

  # ---- Win32API fallback -----------------------------------------------------
  #
  # Windows DLLs do not exist on Android. Instead of aborting the game when a
  # script creates Win32API objects, common user32/kernel32 functions get
  # portable equivalents and everything else becomes a logged no-op that
  # reports success. Game-provided wrappers (e.g. preload/win32_wrap.rb) still win:
  # they redefine Win32API#initialize/#call, which this module calls via super.

  VK_SCANCODES = {
    0x08 => 42, 0x09 => 43, 0x0D => 40, 0x1B => 41, 0x20 => 44, 0x21 => 75, 0x22 => 78,
    0x23 => 77, 0x24 => 74, 0x25 => 80, 0x26 => 82, 0x27 => 79, 0x28 => 81, 0x2D => 73, 0x2E => 76,
    0x10 => [225, 229], 0x11 => [224, 228], 0x12 => [226, 230],
    0xA0 => 225, 0xA1 => 229, 0xA2 => 224, 0xA3 => 228, 0xA4 => 226, 0xA5 => 230,
  }.tap do |table|
    ("A".."Z").each_with_index { |_, i| table[0x41 + i] = 4 + i }
    (1..9).each { |i| table[0x30 + i] = 29 + i }
    table[0x30] = 39
    (0..11).each { |i| table[0x70 + i] = 58 + i }
    (1..9).each { |i| table[0x60 + i] = 88 + i }
    table[0x60] = 98
  end.freeze

  def key_down?(vk)
    case vk
    when 0x01 then return Input.press?(Input::MOUSELEFT)
    when 0x02 then return Input.press?(Input::MOUSERIGHT)
    when 0x04 then return Input.press?(Input::MOUSEMIDDLE)
    end
    codes = Array(VK_SCANCODES[vk])
    return false if codes.empty?
    states = Input.raw_key_states
    codes.any? do |code|
      value = states.is_a?(String) ? states.getbyte(code) : states[code]
      value == true || (value.is_a?(Integer) && value != 0)
    end
  rescue StandardError
    false
  end

  def write_buffer(buffer, bytes)
    return unless buffer.is_a?(String)
    bytes = bytes.b
    count = [bytes.bytesize, buffer.bytesize].min
    buffer.force_encoding(Encoding::BINARY) if buffer.encoding != Encoding::BINARY
    buffer[0, count] = bytes[0, count]
  rescue StandardError
    nil
  end

  def ini_value(file, section, key)
    path = win_path(file.to_s)
    return nil unless File.file?(path)
    current = nil
    File.foreach(path) do |line|
      line = line.strip
      if line.start_with?("[") && line.end_with?("]")
        current = line[1..-2].strip
      elsif current && current.casecmp?(section.to_s) && (index = line.index("="))
        return line[index + 1..-1].strip if line[0, index].strip.casecmp?(key.to_s)
      end
    end
    nil
  rescue StandardError
    nil
  end

  def screen_size
    [Graphics.width, Graphics.height]
  rescue StandardError
    [640, 480]
  end

  def win32_stub(dll, func, args)
    name = func.to_s.sub(/[AW]\z/, "")
    case name
    when "GetAsyncKeyState", "GetKeyState"
      key_down?(args[0].to_i) ? -32768 : 0
    when "GetKeyboardState"
      buffer = args[0]
      (0..255).each { |vk| buffer.setbyte(vk, key_down?(vk) ? 0x80 : 0) } if buffer.is_a?(String) && buffer.bytesize >= 256
      1
    when "GetPrivateProfileString"
      section, key, default, buffer, size, file = args
      value = (ini_value(file, section, key) || default.to_s).to_s
      value = value[0, [size.to_i - 1, 0].max]
      write_buffer(buffer, value + "\0")
      value.bytesize
    when "GetPrivateProfileInt"
      section, key, default, file = args
      (ini_value(file, section, key) || default).to_i
    when "WritePrivateProfileString" then 1
    when "FindWindow", "FindWindowEx", "GetActiveWindow", "GetForegroundWindow", "GetDesktopWindow", "GetFocus" then 1
    when "GetSystemMetrics"
      width, height = screen_size
      { 0 => width, 1 => height, 16 => width, 17 => height }.fetch(args[0].to_i, 0)
    when "GetCursorPos"
      write_buffer(args[0], [Input.mouse_x, Input.mouse_y].pack("l2")) rescue nil
      1
    when "GetClientRect", "GetWindowRect"
      width, height = screen_size
      write_buffer(args[1], [0, 0, width, height].pack("l4"))
      1
    when "ShowCursor"
      Graphics.show_cursor = args[0].to_i != 0 rescue nil
      args[0].to_i != 0 ? 1 : -1
    when "MessageBox"
      log("MessageBox: #{args[1]} / #{args[2]}")
      1
    when "GetUserDefaultLangID", "GetUserDefaultLCID", "GetSystemDefaultLangID" then 0x0409
    when "GetTickCount", "timeGetTime" then (Process.clock_gettime(Process::CLOCK_MONOTONIC) * 1000).to_i & 0xffffffff
    else
      # Unknown functions report success (like mkxp's own Win32API shim):
      # scripts usually test `result != 0` / `== 1` for initialisation calls.
      1
    end
  end

  module Win32Fallback
    def initialize(dll, func, *args, &block)
      @padport_dll = dll.to_s
      @padport_func = func.to_s
      super
    rescue StandardError => e
      @padport_stub = true
      PadPort.log("Win32API #{@padport_dll}:#{@padport_func} is not available on Android (#{e.message.lines.first.to_s.strip}); using a stub")
    end

    def call(*args, &block)
      return super unless @padport_stub
      PadPort.win32_stub(@padport_dll, @padport_func, args.flatten(1))
    end

    def Call(*args, &block)
      call(*args, &block)
    end
  end

  def install_win32_fallback
    return unless defined?(::Win32API)
    ::Win32API.prepend(Win32Fallback)
  end

  # ---- Ruby 1.8 core behaviour (RGSS1 = XP and RGSS2 = VX) -----------------
  #
  # RPG Maker XP/VX embed Ruby 1.8; VX Ace embeds 1.9. Only methods that were
  # removed or changed later are restored, and only for RGSS1/RGSS2 games.

  def rgss_version
    version = (CFG["rgssVersion"] rescue 0).to_i
    version = defined?(::RPG::Cache) ? 1 : 3 if version <= 0
    version
  end

  def install_ruby18_core
    return if rgss_version >= 3
    ::NilClass.class_eval { def id; 4; end } unless nil.respond_to?(:id)
    ::Object.class_eval do
      def id; object_id; end unless method_defined?(:id)
      def type; self.class; end unless method_defined?(:type)
    end
    ::Hash.class_eval { def index(value); key(value); end } unless {}.respond_to?(:index)
    ::Array.class_eval do
      def to_s; join; end
      def nitems; count { |item| !item.nil? }; end unless method_defined?(:nitems)
    end
    ::String.class_eval { def each(*args, &block); each_line(*args, &block); end } unless "".respond_to?(:each)
  end

  # ---- Android display --------------------------------------------------------
  #
  # There is no windowed mode on Android. Games toggle fullscreen at start-up
  # (e.g. To the Moon sends Alt+Enter through keybd_event), which would bring
  # back the status and navigation bars. Keep the game fullscreen.

  def install_android_display
    patch = Module.new do
      def fullscreen=(_value)
        super(true)
      end
    end
    Graphics.singleton_class.prepend(patch) if Graphics.respond_to?(:fullscreen=)
  end

  # ---- mkxp (Ancurio) API used by game-provided preload scripts ------------

  def install_mkxp_api
    return if defined?(::MKXP)
    mkxp = Module.new
    mkxp.define_singleton_method(:puts) { |text| System.puts(text.to_s) }
    mkxp.define_singleton_method(:raw_key_states) do
      states = Input.raw_key_states
      states.is_a?(String) ? states : states.map { |down| down ? 1 : 0 }.pack("C*")
    end
    mkxp.define_singleton_method(:data_directory) { System.data_directory }
    mkxp.define_singleton_method(:method_missing) { |name, *args, &block| System.send(name, *args, &block) }
    mkxp.define_singleton_method(:respond_to_missing?) { |name, priv = false| System.respond_to?(name, priv) }
    Object.const_set(:MKXP, mkxp)
  end
end

PadPort.install_mkxp_api
PadPort.install_ruby18_core
PadPort.install_environment
PadPort.install_windows_paths
PadPort.install_win32_fallback
PadPort.install_android_display
begin
  PadPort.patch_scripts
rescue Exception => e
  PadPort.log("compatibility pass failed: #{e.class}: #{e.message}")
end
