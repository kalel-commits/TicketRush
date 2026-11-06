-- Acquire ALL seat locks or none, atomically (Redis runs a script single-threaded).
-- KEYS = seat lock keys, ARGV[1] = owner token (userId:idempotencyKey), ARGV[2] = TTL in ms.
-- A key already owned by the same token counts as acquired (re-entrant for retries of the same request).
for _, key in ipairs(KEYS) do
  local owner = redis.call('GET', key)
  if owner and owner ~= ARGV[1] then
    return 0
  end
end
for _, key in ipairs(KEYS) do
  redis.call('SET', key, ARGV[1], 'PX', ARGV[2])
end
return 1
