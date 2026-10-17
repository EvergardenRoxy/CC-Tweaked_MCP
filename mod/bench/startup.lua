-- cct-live overhead benchmark (handoff.md V16). Install as startup.lua on computers 0..29.
local kind = os.getComputerID() % 3
local n, last = 0, os.epoch("utc")
while true do
  if kind == 0 then
    os.queueEvent("bench")
    os.pullEvent("bench")
  elseif kind == 1 then
    local f = fs.open("tmp.txt", "w")
    f.write("hello " .. n)
    f.close()
    local g = fs.open("tmp.txt", "r")
    g.readAll()
    g.close()
    if n % 50 == 0 then os.queueEvent("y") os.pullEvent("y") end
  else
    term.setCursorPos(1, n % 18 + 1)
    term.write(("x"):rep(n % 50))
    if n % 20 == 0 then os.queueEvent("y") os.pullEvent("y") end
  end
  n = n + 1
  local now = os.epoch("utc")
  if now - last >= 1000 then
    local f = fs.open("count", "w")
    f.write(tostring(n))
    f.close()
    last = now
  end
end
