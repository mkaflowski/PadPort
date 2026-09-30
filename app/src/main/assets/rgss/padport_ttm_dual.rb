# Optional To the Moon notebook for PadPort. Runs on the RGSS/Ruby thread.
# Only the current party and owned items are exposed; no game state is changed.
module PadPortTTM
  module_function

  def parse_json(text)
    if defined?(HTTPLite::JSON) && HTTPLite::JSON.respond_to?(:parse)
      HTTPLite::JSON.parse(text)
    else
      require "json"
      JSON.parse(text)
    end
  end

  def json(value)
    if defined?(HTTPLite::JSON) && HTTPLite::JSON.respond_to?(:stringify)
      HTTPLite::JSON.stringify(value)
    else
      require "json"
      JSON.generate(value)
    end
  end

  def text(value)
    value.to_s.dup.force_encoding("UTF-8").encode("UTF-8", :invalid => :replace, :undef => :replace)
      .gsub(/\\[CcIi]\[\d+\]/, "")
  end

  def scene_name
    defined?($scene) && $scene ? $scene.class.name.to_s : ""
  end

  def title?
    return true if ["", "Scene_Title", "Scene_Splash"].include?(scene_name)
    return true if defined?($ttm_title_screen) && $ttm_title_screen
    # This release boots directly into its event-driven title map, not Scene_Title.
    return true if defined?($game_map) && $game_map && defined?($data_system) && $data_system &&
      $game_map.map_id == $data_system.start_map_id
    false
  end

  def snapshot(control)
    result = {"epoch" => control["epoch"], "session" => control["session"], "mode" => "title",
              "characters" => [], "notes" => [], "items" => []}
    if title?
      @playing = false
      return result
    end
    return result unless defined?($game_party) && $game_party && defined?($data_items) && $data_items
    # The intro may populate dummy actors before handing control to the player.
    if scene_name == "Scene_Map" && defined?($game_system) && $game_system && !$game_system.menu_disabled
      @playing = true
    end
    return result unless @playing
    return result unless %w[Scene_Map Scene_Menu Scene_Save Scene_Load Scene_End Scene_Options].include?(scene_name)
    result["mode"] = "notebook"
    bios = defined?(CHARACTER_BIO) && CHARACTER_BIO.is_a?(Hash) ? CHARACTER_BIO : {}
    result["characters"] = $game_party.actors.map do |actor|
      {"id" => actor.id, "name" => text(actor.name), "description" => text(bios[actor.id])}
    end
    # Match the game's own Window_Notes / Window_MenuItem, including translated markers.
    marker = defined?(NoteText) ? text(NoteText) : "Note:"
    $data_items.each do |item|
      next unless item
      count = $game_party.item_number(item.id)
      next unless count > 0
      name = text(item.name)
      note = !marker.empty? && name.include?(marker)
      name = name.sub(marker, "").strip if note
      entry = {"id" => item.id, "name" => name, "description" => text(item.description), "count" => count}
      result[note ? "notes" : "items"] << entry
    end
    result
  end

  def tick
    root = ENV["PADPORT_TTM_COMPANION"].to_s
    return if root.empty?
    now = Process.clock_gettime(Process::CLOCK_MONOTONIC)
    return if @next_tick && now < @next_tick
    @next_tick = now + 0.25
    path = File.join(root, "control.json")
    return unless File.file?(path) && File.size(path) <= 8192
    control = parse_json(File.binread(path))
    return unless control["active"] == true
    age = Time.now.to_f * 1000 - control.fetch("updated", 0).to_f
    return unless age >= -1000 && age < 2500
    return unless control["session"].is_a?(String) && control["epoch"].is_a?(Integer)
    payload = json(snapshot(control))
    return if payload == @previous || payload.bytesize > 512 * 1024
    target = File.join(root, "state.json")
    temporary = target + ".ruby-tmp"
    begin
      File.binwrite(temporary, payload)
      File.rename(temporary, target)
      @previous = payload
    ensure
      File.delete(temporary) if File.file?(temporary)
    end
    @reported_error = false
  rescue StandardError, LoadError => e
    unless @reported_error
      PadPort.log("To the Moon companion: #{e.class}: #{e.message}") if defined?(PadPort)
      @reported_error = true
    end
    nil
  end

end

# Frame hook as a classic RGSS alias chain, NOT Module#prepend. This file is a
# preload, so game scripts run after it, and they alias Graphics.update
# themselves - To the Moon's Section114 (Zeriab's F12 pause) does
# `alias_method(:zeriab_f12_pause_update, :update)` + a new `update`. An alias
# taken after a prepend copies the PREPENDED method; its `super` then lands in
# the game's new update, which calls the alias again: SystemStackError on the
# first frame. With alias_method on both sides the chain is simply
# game update -> PadPort update -> original, in any load order.
if defined?(Graphics)
  class << Graphics
    unless method_defined?(:padport_ttm_update)
      alias_method(:padport_ttm_update, :update)
      def update(*args, &block)
        PadPortTTM.tick
        padport_ttm_update(*args, &block)
      end
    end
  end
end
