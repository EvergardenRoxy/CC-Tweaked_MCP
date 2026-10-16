-- Fails at runtime: no monitor is attached.
local function draw()
    local mon = peripheral.find("monitor")
    mon.write("hello")
end

draw()
