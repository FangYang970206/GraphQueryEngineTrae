package com.federatedquery.ast;

import java.util.ArrayList;
import java.util.List;

public class Statement implements AstNode {
    private Query query;
    private boolean isExplain = false;
    private boolean isProfile = false;
    private UsingSnapshot usingSnapshot;
    private ProjectBy projectBy;
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public Query getQuery() {
        return query;
    }
    
    public void setQuery(Query query) {
        this.query = query;
    }
    
    public boolean isExplain() {
        return isExplain;
    }
    
    public void setExplain(boolean explain) {
        isExplain = explain;
    }
    
    public boolean isProfile() {
        return isProfile;
    }
    
    public void setProfile(boolean profile) {
        isProfile = profile;
    }
    
    public UsingSnapshot getUsingSnapshot() {
        return usingSnapshot;
    }
    
    public void setUsingSnapshot(UsingSnapshot usingSnapshot) {
        this.usingSnapshot = usingSnapshot;
    }
    
    public ProjectBy getProjectBy() {
        return projectBy;
    }
    
    public void setProjectBy(ProjectBy projectBy) {
        this.projectBy = projectBy;
    }
    
    @Override
    public String toCypher() {
        StringBuilder sb = new StringBuilder();
        if (isExplain) {
            sb.append("EXPLAIN ");
        }
        if (isProfile) {
            sb.append("PROFILE ");
        }
        if (usingSnapshot != null) {
            sb.append(usingSnapshot.toCypher()).append(" ");
        }
        if (query != null) {
            sb.append(query.toCypher());
        }
        if (projectBy != null) {
            sb.append(" ").append(projectBy.toCypher());
        }
        return sb.toString();
    }
    
    public static class Query implements AstNode {
        private List<SingleQuery> singleQueries = new ArrayList<>();
        private List<UnionClause> unions = new ArrayList<>();
        
        @Override
        public <T> T accept(AstVisitor<T> visitor) {
            return visitor.visit(this);
        }
        
        public List<SingleQuery> getSingleQueries() {
            return singleQueries;
        }
        
        public void setSingleQueries(List<SingleQuery> singleQueries) {
            this.singleQueries = singleQueries;
        }
        
        public List<UnionClause> getUnions() {
            return unions;
        }
        
        public void setUnions(List<UnionClause> unions) {
            this.unions = unions;
        }
        
        @Override
        public String toCypher() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < singleQueries.size(); i++) {
                if (i > 0 && i - 1 < unions.size()) {
                    sb.append(" ").append(unions.get(i - 1).toCypher()).append(" ");
                }
                sb.append(singleQueries.get(i).toCypher());
            }
            return sb.toString();
        }
    }
    
    public static class SingleQuery implements AstNode {
        private List<MatchClause> matchClauses = new ArrayList<>();
        private ReturnClause returnClause;
        private WithClause withClause;
        private WhereClause whereClause;
        private UsingSnapshot usingSnapshot;
        private ProjectBy projectBy;
        
        @Override
        public <T> T accept(AstVisitor<T> visitor) {
            return visitor.visit(this);
        }
        
        public List<MatchClause> getMatchClauses() {
            return matchClauses;
        }
        
        public void setMatchClauses(List<MatchClause> matchClauses) {
            this.matchClauses = matchClauses;
        }
        
        public ReturnClause getReturnClause() {
            return returnClause;
        }
        
        public void setReturnClause(ReturnClause returnClause) {
            this.returnClause = returnClause;
        }
        
        public WithClause getWithClause() {
            return withClause;
        }
        
        public void setWithClause(WithClause withClause) {
            this.withClause = withClause;
        }
        
        public WhereClause getWhereClause() {
            return whereClause;
        }
        
        public void setWhereClause(WhereClause whereClause) {
            this.whereClause = whereClause;
        }
        
        public UsingSnapshot getUsingSnapshot() {
            return usingSnapshot;
        }
        
        public void setUsingSnapshot(UsingSnapshot usingSnapshot) {
            this.usingSnapshot = usingSnapshot;
        }
        
        public ProjectBy getProjectBy() {
            return projectBy;
        }
        
        public void setProjectBy(ProjectBy projectBy) {
            this.projectBy = projectBy;
        }
        
        @Override
        public String toCypher() {
            StringBuilder sb = new StringBuilder();
            for (MatchClause match : matchClauses) {
                sb.append(match.toCypher()).append(" ");
            }
            if (whereClause != null) {
                sb.append(whereClause.toCypher()).append(" ");
            }
            if (withClause != null) {
                sb.append(withClause.toCypher()).append(" ");
            }
            if (returnClause != null) {
                sb.append(returnClause.toCypher());
            }
            if (projectBy != null) {
                sb.append(" ").append(projectBy.toCypher());
            }
            return sb.toString().trim();
        }
    }
}
