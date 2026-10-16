-- Counts the items in every inventory on the network and shows the totals on a monitor.
local mon = peripheral.find("monitor") or error("No monitor attached", 0)
mon.setTextScale(0.5)

local totals = {}
for _, inv in ipairs({ peripheral.find("inventory") }) do
    for _, item in pairs(inv.list()) do
        totals[item.name] = (totals[item.name] or 0) + item.count
    end
end

local names = {}
for name in pairs(totals) do names[#names + 1] = name end
table.sort(names, function(a, b) return totals[a] > totals[b] end)

mon.clear()
mon.setCursorPos(1, 1)
mon.write(("%d kinds"):format(#names))
for i, name in ipairs(names) do
    mon.setCursorPos(1, i + 1)
    mon.write(("%-20s %5d"):format(name:gsub("^minecraft:", ""), totals[name]))
end

-- Move the iron from the first chest into the second one.
local chest = peripheral.wrap("minecraft:chest_0")
print("moved", chest.pushItems("minecraft:chest_1", 1, 32))
print("moved", pcall(chest.pushItems, "minecraft:nowhere", 1))
