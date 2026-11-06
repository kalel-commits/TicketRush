-- Compare-and-delete: remove a seat lock only if THIS owner still holds it, so a slow request whose lock
-- expired can never delete a lock that another user acquired since (spec §9.1).
local released = 0
for _, key in ipairs(KEYS) do
  if redis.call('GET', key) == ARGV[1] then
    redis.call('DEL', key)
    released = released + 1
  end
end
return released
