package com.federatedquery.parser;

import com.federatedquery.ast.Program;
import com.federatedquery.grammar.LcypherLexer;
import com.federatedquery.grammar.LcypherParser;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

@Component
public class CypherParserFacade {
    private final CypherASTVisitor astVisitor;
    private final Cache<String, Program> planCache;
    
    public CypherParserFacade() {
        this.astVisitor = new CypherASTVisitor();
        this.planCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .recordStats()
                .build();
    }
    
    public Program parse(String cypher) {
        if (cypher == null || cypher.trim().isEmpty()) {
            throw new SyntaxErrorException("Cypher query cannot be null or empty");
        }
        
        try {
            LcypherLexer lexer = new LcypherLexer(CharStreams.fromString(cypher));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            LcypherParser parser = new LcypherParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(new SyntaxErrorListener());
            
            LcypherParser.OC_CypherContext ctx = parser.oC_Cypher();
            return astVisitor.visitOC_Cypher(ctx);
        } catch (ParseCancellationException e) {
            throw new SyntaxErrorException("Failed to parse Cypher: " + e.getMessage(), e);
        }
    }
    
    public Program parseCached(String cypher) {
        if (cypher == null || cypher.trim().isEmpty()) {
            throw new SyntaxErrorException("Cypher query cannot be null or empty");
        }
        
        String cacheKey = computeCacheKey(cypher);
        return planCache.get(cacheKey, k -> parse(cypher));
    }
    
    private String computeCacheKey(String cypher) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(cypher.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return cypher;
        }
    }
    
    public Cache<String, Program> getPlanCache() {
        return planCache;
    }
    
    public void clearCache() {
        planCache.invalidateAll();
    }
}
