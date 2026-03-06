package com.library.log.support;

/**
 * 로그 라이브러리 Redis Lua 스크립트 모음.
 *
 * <p>멀티 커맨드 흐름을 서버 측 Lua로 묶어 race condition을 줄이고,
 * 큐 적재/섹션 cursor 갱신을 원자적으로 처리한다.</p>
 */
public final class LogLuaScript {

    /**
     * source key가 비어 있지 않을 때만 snapshot 키로 안전 이동한다.
     *
     * <p>비어 있는 키 rename을 방지하고, 대상 키 충돌 시 이동을 거부한다.</p>
     */
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

    /**
     * 단일 섹션 로그 enqueue 스크립트.
     *
     * <p>queue RPUSH와 section cursor 최대값 갱신을 한 번에 처리한다.</p>
     */
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

    /**
     * 다중 섹션 enqueue 스크립트.
     *
     * <p>section 키 개수만큼 (json, id) 쌍을 순서대로 받아 큐 push 및
     * 섹션 cursor 최대값 갱신을 반복 수행한다.</p>
     */
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

    /**
     * 섹션 cursor 조회 스크립트.
     *
     * <p>키가 없으면 0으로 초기화한 뒤 반환한다.</p>
     */
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

    /**
     * 단일 섹션 cursor 갱신 스크립트.
     *
     * <p>신규 값이 기존 값보다 클 때만 SET한다.</p>
     */
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

    /**
     * 다중 섹션 cursor 일괄 갱신 스크립트.
     *
     * <p>각 키마다 "newVal > current" 조건을 개별 적용하고, 갱신 건수를 반환한다.</p>
     */
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

    /**
     * 유틸 클래스 생성 방지.
     */
    private LogLuaScript() {
    }
}
