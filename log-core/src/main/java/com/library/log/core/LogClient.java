package com.library.log.core;

import com.library.log.dto.LogCursorDto;
import com.library.log.entity.Log;

import java.util.List;

public interface LogClient {

    <T> void enqueue(
            String type,
            T dto,
            String sectionId,
            String createdBy
    );

    <T> void enqueueToSections(
            String type,
            T dto,
            List<String> sectionIds,
            String createdBy
    );

    List<Log> getLog(
            String sectionId,
            Long clientLogId
    );

    List<Log> getLog(
            List<LogCursorDto> cursors
    );
}
