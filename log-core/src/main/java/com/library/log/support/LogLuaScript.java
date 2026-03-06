package com.library.log.support;

public final class LogLuaScript {

    public static final String LUA_SAFE_RENAME = """
        local t = redis.call('TYPE', KEYS[1]).ok
        if t == 'none' then
            return 0
        elseif t == 'list' and redis.call('LLEN',  KEYS[1]) == 0 then
            return 0
        elseif t == 'set'  and redis.call('SCARD', KEYS[1]) == 0 then
            return 0
        elseif t == 'hash' and redis.call('HLEN',  KEYS[1]) == 0 then
            return 0
        end
        return redis.call('RENAMENX', KEYS[1], KEYS[2])
    """;

    public static final String LUA_ENQUEUE = """
        -- KEYS[1] : log:queue
        -- KEYS[2] : log:section:{sectionId}
        -- ARGV[1] : log JSON
        -- ARGV[2] : log ID

        redis.call('RPUSH', KEYS[1], ARGV[1])
        local current = redis.call('GET', KEYS[2])
        if (not current) or (tonumber(ARGV[2]) > tonumber(current)) then
            redis.call('SET', KEYS[2], ARGV[2])
        end
        return 1
    """;

    public static final String LUA_ENQUEUE_MULTI = """
        -- KEYS[1] : log:queue
        -- KEYS[2..n+1] : log:section:{sectionId}
        -- ARGV: (log JSON, log ID) pairs in the same order as section keys

        local count = #KEYS - 1
        if count <= 0 then
            return 0
        end

        for i = 1, count do
            local json = ARGV[(i - 1) * 2 + 1]
            local id = ARGV[(i - 1) * 2 + 2]
            redis.call('RPUSH', KEYS[1], json)
            local current = redis.call('GET', KEYS[i + 1])
            if (not current) or (tonumber(id) > tonumber(current)) then
                redis.call('SET', KEYS[i + 1], id)
            end
        end
        return count
    """;

    public static final String LUA_GET_SECTION_ID = """
        -- KEYS[1]: section_key

        local value = redis.call('GET', KEYS[1])
        if not value then
            redis.call('SET', KEYS[1], '0')
            return '0'
        else
            return value
        end
    """;

    public static final String LUA_SET_SECTION_ID = """
        -- KEYS[1]: section_key
        -- ARGV[1]: last_log_id

        local current = redis.call('GET', KEYS[1])
        if (not current) or (tonumber(ARGV[1]) > tonumber(current)) then
            redis.call('SET', KEYS[1], ARGV[1])
            return 1
        end
        return 0
    """;

    public static final String LUA_SET_SECTION_IDS = """
        -- KEYS: section keys
        -- ARGV: last_log_id for each key (same order)

        local updated = 0
        for i = 1, #KEYS do
            local key = KEYS[i]
            local newVal = ARGV[i]
            if newVal then
                local current = redis.call('GET', key)
                if (not current) or (tonumber(newVal) > tonumber(current)) then
                    redis.call('SET', key, newVal)
                    updated = updated + 1
                end
            end
        end
        return updated
    """;

    private LogLuaScript() {
    }
}
