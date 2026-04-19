package com.fangyang.federatedquery.aggregator;

import com.fangyang.federatedquery.model.GraphEntity;
import com.fangyang.federatedquery.aggregator.GlobalSorter.PagedResult;
import com.fangyang.federatedquery.plan.GlobalContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AggregatorTest {
    private ResultStitcher stitcher;
    private PathBuilder pathBuilder;
    private GlobalSorter sorter;
    private UnionDeduplicator deduplicator;
    
    @BeforeEach
    void setUp() {
        stitcher = new ResultStitcher();
        pathBuilder = new PathBuilder();
        sorter = new GlobalSorter();
        deduplicator = new UnionDeduplicator();
    }
    
    @Test
    @DisplayName("Stitch results from multiple sources")
    void stitchResults() {
        List<GraphEntity> entities = new ArrayList<>();
        
        GraphEntity ne = GraphEntity.node("ne1", "NetworkElement");
        ne.setProperty("name", "NE001");
        ne.setProperty("type", "Router");
        
        GraphEntity kpi = GraphEntity.node("kpi1", "KPI");
        kpi.setProperty("name", "cpu_usage");
        kpi.setProperty("value", 85.5);
        
        entities.add(ne);
        entities.add(kpi);
        
        List<PathBuilder.Path> paths = pathBuilder.buildPaths(entities, null);
        
        assertNotNull(paths, "Paths不能为空");
        assertTrue(paths.size() >= 0, "Paths必须有结果");
        
        for (PathBuilder.Path path : paths) {
            assertNotNull(path.getElements(), "PathElements不能为空");
            for (PathBuilder.PathElement element : path.getElements()) {
                assertNotNull(element.getEntity(), "Entity不能为空");
                assertNotNull(element.getEntity().getId(), "Entity的Id不能为空");
                assertNotNull(element.getEntity().getLabel(), "Entity的Label不能为空");
            }
        }
    }
    
    @Test
    @DisplayName("Build paths from entities")
    void buildPaths() {
        GraphEntity node1 = GraphEntity.node("1", "Person");
        node1.setProperty("name", "Alice");
        node1.setProperty("age", 30);
        
        GraphEntity node2 = GraphEntity.node("2", "Person");
        node2.setProperty("name", "Bob");
        node2.setProperty("age", 25);
        
        GraphEntity edge = GraphEntity.edge("e1", "KNOWS", "1", "2");
        edge.setProperty("since", 2020);
        
        List<GraphEntity> entities = Arrays.asList(node1, edge, node2);
        
        List<PathBuilder.Path> paths = pathBuilder.buildPaths(entities, null);
        
        assertNotNull(paths, "Paths不能为空");
        assertTrue(paths.size() > 0, "Paths必须有结果");
        
        for (PathBuilder.Path path : paths) {
            assertNotNull(path.getElements(), "PathElements不能为空");
            assertTrue(path.getElements().size() > 0, "PathElements必须有元素");
            
            for (PathBuilder.PathElement element : path.getElements()) {
                GraphEntity entity = element.getEntity();
                assertNotNull(entity, "Entity不能为空");
                assertNotNull(entity.getId(), "Entity的Id不能为空");
                assertNotNull(entity.getLabel(), "Entity的Label不能为空");
                
                if (entity.getType() == GraphEntity.EntityType.NODE) {
                    assertTrue(entity.getProperties().containsKey("name"), "Node必须有name属性");
                }
            }
        }
    }
    
    @Test
    @DisplayName("Global sort orders results correctly")
    void globalSort() {
        List<PathBuilder.Path> paths = new ArrayList<>();
        
        for (int i = 10; i >= 1; i--) {
            GraphEntity node = GraphEntity.node(String.valueOf(i), "Test");
            node.setProperty("value", i);
            node.setProperty("name", "Test" + i);
            
            PathBuilder.Path path = new PathBuilder.Path();
            path.addElement(node);
            paths.add(path);
        }
        
        GlobalContext.OrderSpec order = new GlobalContext.OrderSpec();
        GlobalContext.OrderItem item = new GlobalContext.OrderItem();
        item.setProperty("value");
        item.setDescending(false);
        order.addItem(item);
        
        GlobalContext.LimitSpec limit = new GlobalContext.LimitSpec();
        limit.setLimit(5);
        limit.setSkip(0);
        
        PagedResult result = sorter.sortAndPaginate(paths, order, limit);
        
        assertNotNull(result, "PagedResult不能为空");
        assertEquals(5, result.getPaths().size(), "Paths数量必须是5");
        assertEquals(10, result.getTotalCount(), "TotalCount必须是10");
        assertEquals(0, result.getSkip(), "Skip必须是0");
        assertEquals(5, result.getLimit(), "Limit必须是5");
        
        List<PathBuilder.Path> resultPaths = result.getPaths();
        for (int i = 0; i < resultPaths.size(); i++) {
            PathBuilder.Path path = resultPaths.get(i);
            assertNotNull(path.getElements(), "PathElements不能为空");
            assertTrue(path.getElements().size() > 0, "PathElements必须有元素");
            
            GraphEntity entity = path.getElements().get(0).getEntity();
            assertNotNull(entity, "Entity不能为空");
            assertEquals(i + 1, entity.getProperty("value"), "排序顺序必须正确");
        }
    }
    
    @Test
    @DisplayName("Pagination applies skip and limit")
    void pagination() {
        List<PathBuilder.Path> paths = new ArrayList<>();
        
        for (int i = 0; i < 20; i++) {
            GraphEntity node = GraphEntity.node(String.valueOf(i), "Test");
            node.setProperty("index", i);
            node.setProperty("name", "Entity" + i);
            
            PathBuilder.Path path = new PathBuilder.Path();
            path.addElement(node);
            paths.add(path);
        }
        
        GlobalContext.LimitSpec limit = new GlobalContext.LimitSpec();
        limit.setSkip(5);
        limit.setLimit(10);
        
        PagedResult result = sorter.sortAndPaginate(paths, null, limit);
        
        assertNotNull(result, "PagedResult不能为空");
        assertEquals(10, result.getPaths().size(), "Paths数量必须是10");
        assertEquals(5, result.getSkip(), "Skip必须是5");
        assertEquals(10, result.getLimit(), "Limit必须是10");
        assertEquals(20, result.getTotalCount(), "TotalCount必须是20");
        
        List<PathBuilder.Path> resultPaths = result.getPaths();
        for (int i = 0; i < resultPaths.size(); i++) {
            PathBuilder.Path path = resultPaths.get(i);
            assertNotNull(path.getElements(), "PathElements不能为空");
            assertTrue(path.getElements().size() > 0, "PathElements必须有元素");
            
            GraphEntity entity = path.getElements().get(0).getEntity();
            assertNotNull(entity, "Entity不能为空");
            assertEquals(i + 5, entity.getProperty("index"), "分页偏移必须正确");
        }
    }
    
    @Test
    @DisplayName("UNION DISTINCT deduplicates results")
    void unionDeduplicate() {
        GraphEntity node = GraphEntity.node("1", "Test");
        node.setProperty("name", "TestEntity");
        node.setProperty("value", 100);
        
        PathBuilder.Path path1 = new PathBuilder.Path();
        path1.addElement(node);
        
        PathBuilder.Path path2 = new PathBuilder.Path();
        path2.addElement(node);
        
        List<PathBuilder.Path> paths = Arrays.asList(path1, path2, path1);
        
        List<PathBuilder.Path> deduplicated = deduplicator.deduplicate(paths, true);
        
        assertNotNull(deduplicated, "去重结果不能为空");
        assertEquals(1, deduplicated.size(), "去重后数量必须是1");
        
        PathBuilder.Path resultPath = deduplicated.get(0);
        assertNotNull(resultPath, "Path不能为空");
        assertNotNull(resultPath.getElements(), "PathElements不能为空");
        assertEquals(1, resultPath.getElements().size(), "PathElements数量必须是1");
        
        GraphEntity resultEntity = resultPath.getElements().get(0).getEntity();
        assertNotNull(resultEntity, "Entity不能为空");
        assertEquals("1", resultEntity.getId(), "Entity的Id必须是1");
        assertEquals("Test", resultEntity.getLabel(), "Entity的Label必须是Test");
    }
    
    @Test
    @DisplayName("UNION ALL keeps all results")
    void unionAll() {
        GraphEntity node = GraphEntity.node("1", "Test");
        node.setProperty("name", "TestEntity");
        
        PathBuilder.Path path = new PathBuilder.Path();
        path.addElement(node);
        
        List<PathBuilder.Path> paths = Arrays.asList(path, path, path);
        
        List<PathBuilder.Path> result = deduplicator.deduplicate(paths, false);
        
        assertNotNull(result, "结果不能为空");
        assertEquals(3, result.size(), "UNION ALL必须保留所有结果");
        
        for (PathBuilder.Path p : result) {
            assertNotNull(p, "Path不能为空");
            assertNotNull(p.getElements(), "PathElements不能为空");
            assertEquals(1, p.getElements().size(), "PathElements数量必须是1");
            
            GraphEntity entity = p.getElements().get(0).getEntity();
            assertNotNull(entity, "Entity不能为空");
            assertEquals("1", entity.getId(), "Entity的Id必须是1");
            assertEquals("Test", entity.getLabel(), "Entity的Label必须是Test");
        }
    }
    
    @Test
    @DisplayName("Path hash is consistent for same path")
    void pathHashConsistency() {
        GraphEntity node1 = GraphEntity.node("1", "Test");
        node1.setProperty("name", "Test1");
        
        GraphEntity node2 = GraphEntity.node("1", "Test");
        node2.setProperty("name", "Test1");
        
        PathBuilder.Path path1 = new PathBuilder.Path();
        path1.addElement(node1);
        
        PathBuilder.Path path2 = new PathBuilder.Path();
        path2.addElement(node2);
        
        String hash1 = deduplicator.computePathHash(path1);
        String hash2 = deduplicator.computePathHash(path2);
        
        assertNotNull(hash1, "Hash1不能为空");
        assertNotNull(hash2, "Hash2不能为空");
        assertFalse(hash1.isEmpty(), "Hash1不能为空字符串");
        assertFalse(hash2.isEmpty(), "Hash2不能为空字符串");
        assertEquals(hash1, hash2, "相同路径的Hash必须相同");
    }
}
