-- H1 probe: what can the program under test see of the debugging/introspection toolbox?
local function t(name) return type(debug[name]) end
print("debug_type", type(debug))
for _, k in ipairs({ "getlocal", "setlocal", "getupvalue", "setupvalue", "getregistry", "sethook",
    "gethook", "getinfo", "traceback", "getmetatable", "setmetatable", "upvalueid", "getfenv", "setfenv" }) do
  print("DEBUGFN", k, t(k))
end

-- Can we read the registry, and does it hold any thread (main thread reference)?
local reg = debug.getregistry()
local n, threads = 0, 0
for k, v in pairs(reg) do
  n = n + 1
  if type(v) == "thread" then threads = threads + 1 end
  print("REG", tostring(k), type(v), tostring(v):sub(1, 70))
end
print("REGISTRY", n, "threads", threads)

-- Does debug.getlocal accept a thread argument (a fresh coroutine)?
local co = coroutine.create(function(a, b) local x = 1; coroutine.yield() end)
coroutine.resume(co, 10, 20)
local ok, name, val = pcall(debug.getlocal, co, 1, 1)
print("GETLOCAL_THREAD", ok, tostring(name), tostring(val))

-- Walk the current thread's stack and list every local name/value we can see.
for lvl = 0, 12 do
  local i = 1
  while true do
    local nm, v = debug.getlocal(lvl, i)
    if not nm then break end
    print("LOCAL", lvl, i, tostring(nm), type(v), tostring(v):sub(1, 50))
    i = i + 1
  end
end

-- Enumerate every function reachable from _G and check its upvalues for a report-like API.
local found = 0
for gk, gv in pairs(_G) do
  if type(gv) == "function" then
    for ui = 1, 20 do
      local un, uv = debug.getupvalue(gv, ui)
      if not un then break end
      if type(uv) == "table" and (rawget(uv, "done") or rawget(uv, "start")) then
        found = found + 1
        print("UPVALUE_REPORT", tostring(gk), un, tostring(uv))
      end
    end
  end
end
print("REPORT_UPVALUES", found)
print("__cctrun global", tostring(rawget(_G, "__cctrun")))
