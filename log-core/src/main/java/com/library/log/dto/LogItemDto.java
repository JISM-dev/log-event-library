package com.library.log.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LogItemDto {

    private Long id;
    private String sectionId;
    private String type;
    private String payload;
    private String createdBy;
    private String createdTime;
}
