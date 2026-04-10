package com.federatedquery.mockserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Collectors;

public class MockDataRepository {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private final List<Map<String, Object>> alarmData = new ArrayList<>();
    private final List<Map<String, Object>> kpiData = new ArrayList<>();
    private final List<Map<String, Object>> kpi2Data = new ArrayList<>();
    
    public MockDataRepository() {
        initializeAlarmData();
        initializeKpiData();
        initializeKpi2Data();
    }
    
    private void initializeAlarmData() {
        Map<String, Object> alarm1 = new HashMap<>();
        alarm1.put("CSN", "0079bb0f-0e0a-44de-b595-cab5c22324ef");
        alarm1.put("MENAME", "ALARM001");
        alarm1.put("MEDN", "388581df-50a3-4d9f-96d4-15faf36f9caf");
        alarm1.put("OCCURTIME", 1775825151299L);
        alarm1.put("CLEARTIME", 1775825951299L);
        alarmData.add(alarm1);
        
        Map<String, Object> alarm2 = new HashMap<>();
        alarm2.put("CSN", "afa87ad3-0834-467c-ba2e-1315fb9ba0cb");
        alarm2.put("MENAME", "ALARM002");
        alarm2.put("MEDN", "388581df-50a3-4d9f-96d4-15faf36f9caf");
        alarm2.put("OCCURTIME", 1775825251299L);
        alarm2.put("CLEARTIME", 1775825551299L);
        alarmData.add(alarm2);
        
        Map<String, Object> alarm3 = new HashMap<>();
        alarm3.put("CSN", "0148b488-f857-40a3-8e85-7b5ecf16f6ac");
        alarm3.put("MENAME", "ALARM003");
        alarm3.put("MEDN", "a158b427-4a65-4adf-9096-3c8225519cca");
        alarm3.put("OCCURTIME", 1775825151299L);
        alarm3.put("CLEARTIME", 1775825951299L);
        alarmData.add(alarm3);
    }
    
    private void initializeKpiData() {
        Map<String, Object> kpi1 = new HashMap<>();
        kpi1.put("resId", "0079bb0f-0e0a-44de-b595-cab5c22324ef");
        kpi1.put("name", "KPI001");
        kpi1.put("parentResId", "eccc2c94-6a31-45ea-a16e-0c709939cbe5");
        kpi1.put("time", 1775825156755L);
        kpiData.add(kpi1);
        
        Map<String, Object> kpi2 = new HashMap<>();
        kpi2.put("resId", "5af68cc1-3fad-486e-8073-cb0d1181a3e4");
        kpi2.put("name", "KPI001");
        kpi2.put("parentResId", "552dddad-84c4-4d5e-9014-481443ec23b5");
        kpi2.put("time", 1775825856755L);
        kpiData.add(kpi2);
    }
    
    private void initializeKpi2Data() {
        Map<String, Object> kpi21 = new HashMap<>();
        kpi21.put("resId", "90d7f2d4-4822-438e-a0cd-b2e7011e9786");
        kpi21.put("name", "KPI2001");
        kpi21.put("parentResId", "db82ad76-8bdc-4f4b-96a0-a5e91c6861fb");
        kpi21.put("time", 1775825256755L);
        kpi2Data.add(kpi21);
        
        Map<String, Object> kpi22 = new HashMap<>();
        kpi22.put("resId", "5ff8f0f4-77a3-403d-889a-d615b4fd586a");
        kpi22.put("name", "KPI2002");
        kpi22.put("parentResId", "c889973b-8bd7-4eb9-899b-b1cc9bf18a2e");
        kpi22.put("time", 1775825556755L);
        kpi2Data.add(kpi22);
        
        Map<String, Object> kpi23 = new HashMap<>();
        kpi23.put("resId", "0036d5a5-ae90-4c76-98d8-113c616d9e8d");
        kpi23.put("name", "KPI2003");
        kpi23.put("parentResId", "537ae2db-80d6-4b13-9cee-140a9af811ca");
        kpi23.put("time", 1775822856755L);
        kpi2Data.add(kpi23);
        
        Map<String, Object> kpi24 = new HashMap<>();
        kpi24.put("resId", "d53fcfab-d0cb-4fe5-84a9-73de0f0b2850");
        kpi24.put("name", "KPI2004");
        kpi24.put("parentResId", "7c013137-db19-4f21-bbbd-6908cd2033d8");
        kpi24.put("time", 1775825951299L);
        kpi2Data.add(kpi24);
    }
    
    public List<Map<String, Object>> queryAlarmsByMedn(String medn) {
        return alarmData.stream()
                .filter(alarm -> medn.equals(alarm.get("MEDN")))
                .collect(Collectors.toList());
    }
    
    public List<Map<String, Object>> queryAlarmsByMednAndTime(String medn, Long timestamp, String strategy) {
        return alarmData.stream()
                .filter(alarm -> medn.equals(alarm.get("MEDN")))
                .filter(alarm -> filterByTime(alarm, timestamp, strategy, "OCCURTIME"))
                .collect(Collectors.toList());
    }
    
    public List<Map<String, Object>> queryKpiByParentResId(String parentResId) {
        List<Map<String, Object>> result = new ArrayList<>();
        
        result.addAll(kpiData.stream()
                .filter(kpi -> parentResId.equals(kpi.get("parentResId")))
                .collect(Collectors.toList()));
        
        result.addAll(kpi2Data.stream()
                .filter(kpi -> parentResId.equals(kpi.get("parentResId")))
                .collect(Collectors.toList()));
        
        return result;
    }
    
    public List<Map<String, Object>> queryKpiByParentResIdAndTime(String parentResId, Long timestamp, String strategy) {
        List<Map<String, Object>> result = new ArrayList<>();
        
        result.addAll(kpiData.stream()
                .filter(kpi -> parentResId.equals(kpi.get("parentResId")))
                .filter(kpi -> filterByTime(kpi, timestamp, strategy, "time"))
                .collect(Collectors.toList()));
        
        result.addAll(kpi2Data.stream()
                .filter(kpi -> parentResId.equals(kpi.get("parentResId")))
                .filter(kpi -> filterByTime(kpi, timestamp, strategy, "time"))
                .collect(Collectors.toList()));
        
        return result;
    }
    
    private boolean filterByTime(Map<String, Object> record, Long targetTimestamp, String strategy, String timeField) {
        if (targetTimestamp == null) {
            return true;
        }
        
        Long recordTime = (Long) record.get(timeField);
        if (recordTime == null) {
            return false;
        }
        
        if ("nearest".equalsIgnoreCase(strategy)) {
            return true;
        } else if ("latest".equalsIgnoreCase(strategy) || "ffill".equalsIgnoreCase(strategy)) {
            return recordTime <= targetTimestamp;
        }
        
        return true;
    }
    
    public List<Map<String, Object>> projectFields(List<Map<String, Object>> data, List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return data;
        }
        
        return data.stream()
                .map(record -> {
                    Map<String, Object> projected = new HashMap<>();
                    for (String field : fields) {
                        if (record.containsKey(field)) {
                            projected.put(field, record.get(field));
                        }
                    }
                    return projected;
                })
                .collect(Collectors.toList());
    }
    
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
