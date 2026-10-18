-- H2 probe: are fs writes limited by the reported 1,000,000-byte capacity?
local f = assert(fs.open("big", "w"))
local chunk = string.rep("A", 1024 * 1024)
for i = 1, 20 do f.write(chunk) end
f.close()
print("size", fs.getSize("big"))
print("free", fs.getFreeSpace(""))
print("capacity", fs.getCapacity(""))
local g = assert(fs.open("small", "w"))
g.write("x")
g.close()
print("free2", fs.getFreeSpace(""))
