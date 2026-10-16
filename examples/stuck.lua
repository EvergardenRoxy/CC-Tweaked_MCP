-- Waits for a redstone signal that never comes: the run times out.
print("waiting for redstone...")
local function wait_for_signal()
    os.pullEvent("redstone")
end
wait_for_signal()
