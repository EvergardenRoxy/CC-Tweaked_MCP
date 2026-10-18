-- F10 probe: slot argument types on an inventory peripheral (game vs engine behaviour).
local inv = peripheral.wrap("back")
print("type", type(inv))
for _, arg in ipairs({ 1, 1.5, 1.0, "1", 0, -1, 4 }) do
  local ok, v = pcall(inv.getItemDetail, arg)
  print("getItemDetail", tostring(arg), ok, type(v), tostring(v))
end
print("size", tostring(inv.size()))
