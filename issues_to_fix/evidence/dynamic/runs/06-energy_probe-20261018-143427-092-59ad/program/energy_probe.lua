-- F3 probe: what does a fixture energy capacity of 1e999 (~Infinity) become in the running peripheral?
local e = peripheral.wrap("back")
print("type", type(e))
for _, m in ipairs({ "getEnergy", "getEnergyCapacity", "getMaxEnergy", "getEnergyStored" }) do
  local ok, v = pcall(function() return e[m]() end)
  print(m, ok, tostring(v))
end
