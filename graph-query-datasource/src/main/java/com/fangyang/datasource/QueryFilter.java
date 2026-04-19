package com.fangyang.datasource;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryFilter {
    private String key;
    private String operator;
    private Object value;
}
