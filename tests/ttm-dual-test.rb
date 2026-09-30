# Plain Ruby 3.1+ checks; also runs with jruby-complete, no gems or game execution.
require "json"
require "tmpdir"
module Graphics
  def self.update
    @frames = (@frames || 0) + 1
  end
end
load File.expand_path("../app/src/main/assets/rgss/padport_ttm_dual.rb", __dir__)

def check(value, message)
  raise message unless value
end
class Scene_Map; end
class Scene_Menu; end
class Scene_Title; end
class Scene_Load; end
Actor = Struct.new(:id, :name)
Item = Struct.new(:id, :name, :description)
Party = Struct.new(:actors, :counts) do
  def item_number(id); counts.fetch(id, 0); end
end
NoteText = "Note:"
CHARACTER_BIO = {1 => "Memory specialist", 2 => "Unmet character"}
$data_system = Struct.new(:start_map_id).new(1)
$game_map = Struct.new(:map_id).new(1)
$game_system = Struct.new(:menu_disabled).new(false)
$game_party = Party.new([Actor.new(1, "Eva")], {1 => 1, 2 => 2})
$data_items = [nil, Item.new(1,"Note: Lighthouse","A collected observation"),
  Item.new(2,"Paper rabbit","A description"),Item.new(3,"Note: Future secret","Must stay hidden")]
$scene = Scene_Map.new
$ttm_title_screen = false
control = {"epoch" => 1, "session" => "test"}

state = PadPortTTM.snapshot(control)
check(state["mode"] == "title" && state["characters"].empty?, "Event-driven title map exposed dummy actors")
$game_map.map_id = 2
$game_system.menu_disabled = true
check(PadPortTTM.snapshot(control)["notes"].empty?, "Intro exposed notes before gameplay")
$game_system.menu_disabled = false
state = PadPortTTM.snapshot(control)
check(state["characters"].map { |a| a["name"] } == ["Eva"], "Non-party characters exposed")
check(state["notes"].map { |a| a["id"] } == [1], "Unowned note exposed")
check(state["items"].map { |a| a["id"] } == [2], "Items incorrectly classified")
check(state["items"][0]["count"] == 2, "Quantity lost")
check(state["characters"][0]["description"] == CHARACTER_BIO[1], "Game biography not used")
$scene = Scene_Menu.new
check(PadPortTTM.snapshot(control)["mode"] == "notebook", "Notebook disappeared in game menu")

Object.send(:remove_const, :NoteText)
NoteText = "Notatka:"
$data_items[1].name = "Notatka: Latarnia"
$data_items[1].description = "Żółta łódź — obserwacja."
state = PadPortTTM.snapshot(control)
check(state["notes"][0]["name"] == "Latarnia", "Localized marker not recognized")
check(JSON.parse(PadPortTTM.json(state))["notes"][0]["description"] == $data_items[1].description, "Unicode round-trip failed")
$game_party.counts[1] = 0
check(PadPortTTM.snapshot(control)["notes"].empty?, "Removed note stayed in notebook")
$game_party.counts[1] = 1
$ttm_title_screen = true
check(PadPortTTM.snapshot(control)["characters"].empty?, "Returning to title leaked previous party")
$ttm_title_screen = false
$scene = Scene_Load.new
check(PadPortTTM.snapshot(control)["items"].empty?, "Load menu exposed previous game data")
$scene = Scene_Map.new

Dir.mktmpdir("padport-ttm") do |folder|
  ENV["PADPORT_TTM_COMPANION"] = folder
  control.merge!("active" => true, "updated" => (Time.now.to_f * 1000).to_i)
  File.binwrite(File.join(folder,"control.json"), JSON.generate(control))
  Graphics.update
  output = File.join(folder,"state.json")
  check(JSON.parse(File.binread(output))["notes"].size == 1, "Ruby bridge did not publish current state")
  check(!File.exist?(output + ".ruby-tmp"), "Temporary output left behind")
  old = File.binread(output)
  control["updated"] = 0; control["epoch"] = 2
  File.binwrite(File.join(folder,"control.json"), JSON.generate(control))
  PadPortTTM.instance_variable_set(:@next_tick,nil); Graphics.update
  check(File.binread(output) == old, "Expired native heartbeat accepted")
  control["updated"] = (Time.now.to_f * 1000).to_i
  File.binwrite(File.join(folder,"control.json"), JSON.generate(control))
  PadPortTTM.instance_variable_set(:@next_tick,nil); Graphics.update
  check(JSON.parse(File.binread(output))["epoch"] == 2, "New display generation not published")
  File.binwrite(File.join(folder,"control.json"), "broken")
  PadPortTTM.instance_variable_set(:@next_tick,nil); Graphics.update
  check(Graphics.instance_variable_get(:@frames) == 4, "Transport error interrupted Graphics.update")
  ENV.delete("PADPORT_TTM_COMPANION")
end

# Regression: game scripts run AFTER this preload and alias Graphics.update.
# To the Moon's Section114 (Zeriab's F12 pause), verbatim structure. With the
# old Module#prepend hook this recursed forever (SystemStackError, line 111).
class Reset < Exception; end
module Graphics
  class << self
    unless self.method_defined?(:zeriab_f12_pause_update)
      alias_method(:zeriab_f12_pause_update, :update)
    end
    def update(*args)
      begin
        zeriab_f12_pause_update(*args)
        return
      rescue Reset
        # F12 pause loop of the original script - not needed here
      end
    end
  end
end
ticks = 0
PadPortTTM.singleton_class.send(:alias_method, :__test_tick, :tick)
PadPortTTM.define_singleton_method(:tick) { ticks += 1; __test_tick }
frames = Graphics.instance_variable_get(:@frames)
begin
  3.times { Graphics.update }
rescue SystemStackError
  raise "Graphics.update recursion with a later game alias (To the Moon F12 pause)"
end
check(Graphics.instance_variable_get(:@frames) == frames + 3, "Original Graphics.update lost behind a later game alias")
check(ticks == 3, "Companion tick lost behind a later game alias")
load File.expand_path("../app/src/main/assets/rgss/padport_ttm_dual.rb", __dir__)   # loaded twice: no double hook
# (the reload redefines PadPortTTM.tick, so count it again)
PadPortTTM.singleton_class.send(:alias_method, :__test_tick, :tick)
PadPortTTM.define_singleton_method(:tick) { ticks += 1; __test_tick }
Graphics.update
check(ticks == 4 && Graphics.instance_variable_get(:@frames) == frames + 4, "Reloading the preload hooked update twice")

if ENV["TTM_MENU_SCRIPT"]
  source = File.read(ENV.fetch("TTM_MENU_SCRIPT"), encoding: "UTF-8")
  source = source.gsub("\r\n", "\n")
  # Compare the adapter's selection to the actual game get_items implementations.
  selectors = ["Window_Notes", "Window_MenuItem"].map do |name|
    body = source.match(/class #{name} < [^\n]+.*?  def get_items\n(.*?)\n  end/m)[1]
    klass = Class.new
    klass.class_eval("def collect; @data = []; #{body}; @data; end")
    klass.new.collect.map(&:id)
  end
  state = PadPortTTM.snapshot(control)
  check(selectors[0] == state["notes"].map { |a| a["id"] }, "Notes differ from actual To the Moon menu")
  check(selectors[1] == state["items"].map { |a| a["id"] }, "Items differ from actual To the Moon menu")
  puts "Actual To the Moon note/item selection: OK"
end
puts "To the Moon companion: title/intro, notebook, ownership, localization, menu, load, IPC, Graphics hook and game alias chain OK"
