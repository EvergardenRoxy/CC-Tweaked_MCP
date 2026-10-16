-- Keeps allocating 16 MB strings until the heap limit (1 GB by default) is reached.
-- Expected result: status "out_of_memory", exit code 4.
local kept, n = {}, 0
print("allocating")
while true do
  n = n + 1
  kept[n] = string.rep("x", 16 * 1024 * 1024) .. n
  if n % 4 == 0 then
    os.queueEvent("tick")
    os.pullEvent("tick") -- yield, or CC stops the program for not yielding
  end
end
