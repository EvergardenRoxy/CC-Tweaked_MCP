-- SPDX-License-Identifier: MPL-2.0
-- Review-only probes against the supplied delivery, inside cct-run's memory filesystem.
local B = "/nf-precision-test"
local cfg0 = dofile(B .. "/config.lua")
local util = dofile(B .. "/util.lua")
local Store = dofile(B .. "/store.lua")
local Engine = dofile(B .. "/engine.lua")
local Mock = dofile(B .. "/mockhw.lua")
local Hw = dofile(B .. "/hw.lua")
local App = dofile(B .. "/app.lua")
fs.makeDir("/review")
local results = {}
local function emit(name, result)
  result.name = name
  results[#results + 1] = result
end
local function config(name)
  local c = util.copy(cfg0)
  c.STATE = "/review/" .. name .. ".txt"
  c.LOGFILE = "/review/" .. name .. ".log"
  return c
end
local function write(path, text)
  local f = assert(fs.open(path, "w")); f.write(text); f.close()
end
local function read(path)
  local f = assert(fs.open(path, "r")); local s = f.readAll();f.close();return s
end
local function env(name)
  local c = config(name)
  local store = Store.new(c)
  local mock = Mock.new(c, { outcomes = { "success" } })
  mock:add(c.P.vault_sheet, c.ITEM.sheet, 10)
  for _, mat in ipairs(c.SEQ) do mock:add(c.P.vault_aux, mat, 50) end
  local eng = Engine.new({ cfg = c, util = util, store = store, hw = mock })
  eng:init()
  return c, store, mock, eng
end

do
  local c = config("backup")
  local s = Store.new(c)
  s:save({ marker = 1 });s:save({ marker = 2 });s:save({ marker = 3 })
  local backup = textutils.unserialise(read(c.STATE .. ".bak"))
  write(c.STATE, "{invalid")
  local loaded = s:load()
  emit("stale_backup", { current_before_corruption = 3, expected_previous = 2, backup_marker = backup.marker, recovered_marker = loaded.marker })
end

do
  local c, s, m, e = env("corruption")
  e:order("OLD", "attempts", 1, 1)
  write(c.STATE, "{invalid");write(c.STATE .. ".bak", "{invalid")
  local restarted = Engine.new({ cfg = c, util = util, store = s, hw = m })
  restarted:init();restarted:recover()
  local old_present = restarted.st.orders.OLD ~= nil
  local accepted = restarted:order("OLD", "attempts", 1, 1)
  emit("corrupt_state_reset", { old_id_retained = old_present, corruption_alert = restarted.st.alert or "", same_id_accepted = accepted })
end

do
  local c, s, m, e = env("savefailure")
  e:order("DISK", "attempts", 1, 1)
  s.save = function() return false, "injected disk full" end
  for _ = 1, 5 do e:tick() end
  emit("save_failure_continues_feed", { state = e.st.task.state, sheet_pushes = m.sheet_pushes, has_alert = e.st.alert ~= nil })
end

do
  local c = config("chat")
  local real_wrap = peripheral.wrap
  local calls = 0
  peripheral.wrap = function(name)
    if name == c.P.chat then return { sendFormattedMessage = function() calls = calls + 1; return false, "cooldown" end } end
    return real_wrap(name)
  end
  local hw = Hw.new(c)
  local app = App.new({ cfg = c, hw = hw, eng = {}, display = {}, clock = function() return 100 end })
  app:say("must retry");app:flush_chat()
  peripheral.wrap = real_wrap
  emit("chat_false_acknowledged", { actual_accepts = 0, api_calls = calls, reported_replies = app.replies, queued = #app.chat })
end

do
  local c = config("queues")
  local logs = {}
  local app = App.new({ cfg = c, util = util, hw = {}, eng = {}, display = {}, verbose = function(tag,text) logs[#logs+1]={tag,text} end })
  for i = 1, 21 do app:on_event("chat", "player", "!nf query X" .. i) end
  local command_head = app.cmd_queue[1]
  local command_logs = #logs
  for i = 1, 21 do app:say("reply" .. i) end
  emit("queue_drop_without_audit", { queued_commands = #app.cmd_queue, first_command = command_head, received_command_logs = command_logs, queued_replies = #app.chat, first_reply = app.chat[1], total_logs = #logs })
end

do
  local c = config("adapter_failure")
  local real_wrap = peripheral.wrap
  peripheral.wrap = function(name)
    if name == c.P.display then return {
      clear = function() error("disconnected") end,
      setCursorPos = function() error("disconnected") end,
      writeBytes = function() error("disconnected") end,
      update = function() error("disconnected") end,
    } end
    return real_wrap(name)
  end
  local reported_ok = Hw.new(c):display({"test"})
  peripheral.wrap = real_wrap
  emit("display_failure_acknowledged", { reported_ok = reported_ok })
end
local encoded = textutils.serialiseJSON(results):gsub(".", function(c) return string.format("%02x", c:byte()) end)
print("REVIEW_HEX " .. encoded)
