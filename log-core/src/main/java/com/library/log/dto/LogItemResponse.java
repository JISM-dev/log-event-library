package com.library.log.dto;

import com.library.log.entity.Log;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import static com.library.log.utils.StringUtil.toStringOrNull;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LogItemResponse {

    private String id;
    private String sectionId;
    private String createdBy;
    private String type;
    private String payload;
    private String createdTime;

    public LogItemResponse(Log log) {
        if (log == null) {
            return;
        }

        this.id = toStringOrNull(log.getId());
        this.sectionId = log.getSectionId();
        this.createdBy = log.getCreatedBy();
        this.type = log.getType();
        this.payload = log.getPayload();
        this.createdTime = log.getCreatedDate() == null ? null : log.getCreatedDate().toString();
    }
}
