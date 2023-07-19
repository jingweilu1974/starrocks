// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.
package com.starrocks.catalog;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.google.gson.annotations.SerializedName;
import com.starrocks.analysis.DescriptorTable.ReferencedPartitionInfo;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.FunctionCallExpr;
import com.starrocks.analysis.SlotDescriptor;
import com.starrocks.analysis.SlotId;
import com.starrocks.analysis.SlotRef;
import com.starrocks.analysis.StringLiteral;
import com.starrocks.analysis.TableName;
import com.starrocks.analysis.UserIdentity;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Pair;
import com.starrocks.common.UserException;
import com.starrocks.common.io.DeepCopy;
import com.starrocks.common.io.Text;
import com.starrocks.common.util.DateUtils;
import com.starrocks.common.util.PropertyAnalyzer;
import com.starrocks.common.util.TimeUtils;
import com.starrocks.connector.ConnectorTableInfo;
import com.starrocks.connector.PartitionUtil;
import com.starrocks.mysql.privilege.Auth;
import com.starrocks.persist.gson.GsonPostProcessable;
import com.starrocks.persist.gson.GsonUtils;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.AnalyzeState;
import com.starrocks.sql.analyzer.ExpressionAnalyzer;
import com.starrocks.sql.analyzer.Field;
import com.starrocks.sql.analyzer.RelationFields;
import com.starrocks.sql.analyzer.RelationId;
import com.starrocks.sql.analyzer.Scope;
import com.starrocks.sql.common.PartitionDiff;
import com.starrocks.sql.common.SyncPartitionUtils;
import com.starrocks.sql.common.UnsupportedException;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.base.ColumnRefFactory;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.statistic.StatsConstants;
import com.starrocks.thrift.TTableDescriptor;
import com.starrocks.thrift.TTableType;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * meta structure for materialized view
 */
public class MaterializedView extends OlapTable implements GsonPostProcessable {
    private static final Logger LOG = LogManager.getLogger(MaterializedView.class);

    public enum RefreshType {
        SYNC,
        ASYNC,
        MANUAL,
        INCREMENTAL
    }

    public static class BasePartitionInfo {

        @SerializedName(value = "id")
        private long id;

        @SerializedName(value = "version")
        private long version;

        public BasePartitionInfo(long id, long version) {
            this.id = id;
            this.version = version;
        }

        public static BasePartitionInfo fromExternalTable(com.starrocks.connector.PartitionInfo info) {
            // TODO: id and version
            return new BasePartitionInfo(-1, -1, info.getModifiedTime());
        }

        public static BasePartitionInfo fromOlapTable(Partition partition) {
            return new BasePartitionInfo(partition.getId(), partition.getVisibleVersion(), -1);
        }

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getVersion() {
            return version;
        }

        public void setVersion(long version) {
            this.version = version;
        }
    }

    public static class AsyncRefreshContext {
        // base table id -> (partition name -> partition info (id, version))
        // partition id maybe changed after insert overwrite, so use partition name as key.
        // partition id which in BasePartitionInfo can be used to check partition is changed
        @SerializedName("baseTableVisibleVersionMap")
        private final Map<Long, Map<String, BasePartitionInfo>> baseTableVisibleVersionMap;

        @SerializedName("baseTableInfoVisibleVersionMap")
        private final Map<BaseTableInfo, Map<String, BasePartitionInfo>> baseTableInfoVisibleVersionMap;

        @SerializedName(value = "defineStartTime")
        private boolean defineStartTime;

        @SerializedName(value = "starTime")
        private long startTime;

        @SerializedName(value = "step")
        private long step;

        @SerializedName(value = "timeUnit")
        private String timeUnit;

        public AsyncRefreshContext() {
            this.baseTableVisibleVersionMap = Maps.newHashMap();
            this.baseTableInfoVisibleVersionMap = Maps.newHashMap();
            this.defineStartTime = false;
            this.startTime = Utils.getLongFromDateTime(LocalDateTime.now());
            this.step = 0;
            this.timeUnit = null;
        }

        public Map<Long, Map<String, BasePartitionInfo>> getBaseTableVisibleVersionMap() {
            return baseTableVisibleVersionMap;
        }

        public Map<BaseTableInfo, Map<String, BasePartitionInfo>> getBaseTableInfoVisibleVersionMap() {
            return baseTableInfoVisibleVersionMap;
        }

        public void clearVisibleVersionMap() {
            this.baseTableInfoVisibleVersionMap.clear();
            this.baseTableVisibleVersionMap.clear();
        }

        public boolean isDefineStartTime() {
            return defineStartTime;
        }

        public void setDefineStartTime(boolean defineStartTime) {
            this.defineStartTime = defineStartTime;
        }

        public long getStartTime() {
            return startTime;
        }

        public void setStartTime(long startTime) {
            this.startTime = startTime;
        }

        public long getStep() {
            return step;
        }

        public void setStep(long step) {
            this.step = step;
        }

        public String getTimeUnit() {
            return timeUnit;
        }

        public void setTimeUnit(String timeUnit) {
            this.timeUnit = timeUnit;
        }

        @Override
        public String toString() {
            return "AsyncRefreshContext{" +
                    "baseTableVisibleVersionMap=" + baseTableVisibleVersionMap +
                    ", defineStartTime=" + defineStartTime +
                    ", startTime=" + startTime +
                    ", step=" + step +
                    ", timeUnit='" + timeUnit + '\'' +
                    '}';
        }
    }

    public static class MvRefreshScheme {
        @SerializedName(value = "type")
        private RefreshType type;
        // when type is ASYNC
        // asyncRefreshContext is used to store refresh context
        @SerializedName(value = "asyncRefreshContext")
        private AsyncRefreshContext asyncRefreshContext;
        @SerializedName(value = "lastRefreshTime")
        private long lastRefreshTime;

        public MvRefreshScheme() {
            this.type = RefreshType.ASYNC;
            this.asyncRefreshContext = new AsyncRefreshContext();
            this.lastRefreshTime = 0;
        }
<<<<<<< HEAD
=======

        public MvRefreshScheme(RefreshType type) {
            this.type = type;
            this.moment = RefreshMoment.IMMEDIATE;
            this.asyncRefreshContext = new AsyncRefreshContext();
            this.lastRefreshTime = 0;
        }

        public boolean isIncremental() {
            return this.type.equals(RefreshType.INCREMENTAL);
        }

        public boolean isSync() {
            return this.type.equals(RefreshType.SYNC);
        }

        public RefreshMoment getMoment() {
            return moment;
        }

        public void setMoment(RefreshMoment moment) {
            this.moment = moment;
        }
>>>>>>> 4277d9435f ([Enhancement] introduce loose query rewrite mode (#27280))

        public RefreshType getType() {
            return type;
        }

        public void setType(RefreshType type) {
            this.type = type;
        }

        public AsyncRefreshContext getAsyncRefreshContext() {
            return asyncRefreshContext;
        }

        public void setAsyncRefreshContext(AsyncRefreshContext asyncRefreshContext) {
            this.asyncRefreshContext = asyncRefreshContext;
        }

        public long getLastRefreshTime() {
            return lastRefreshTime;
        }

        public void setLastRefreshTime(long lastRefreshTime) {
            this.lastRefreshTime = lastRefreshTime;
        }
    }

    @SerializedName(value = "dbId")
    private long dbId;

    @SerializedName(value = "refreshScheme")
    private MvRefreshScheme refreshScheme;

    @SerializedName(value = "baseTableIds")
    private Set<Long> baseTableIds;

    @SerializedName(value = "baseTableInfos")
    private List<BaseTableInfo> baseTableInfos;

    @SerializedName(value = "active")
    private boolean active;

    // TODO: now it is original definition sql
    // for show create mv, constructing refresh job(insert into select)
    @SerializedName(value = "viewDefineSql")
    private String viewDefineSql;

    @SerializedName(value = "simpleDefineSql")
    private String simpleDefineSql;

    // record expression table column
    @SerializedName(value = "partitionRefTableExprs")
    private List<Expr> partitionRefTableExprs;

    public static class MvRewriteContext {
        // mv's logical plan
        private final OptExpression logicalPlan;

        // mv plan's output columns, used for mv rewrite
        private final List<ColumnRefOperator> outputColumns;

        // column ref factory used when compile mv plan
        private final ColumnRefFactory refFactory;

        // indidate whether this mv is a SPJG plan
        // if not, we do not store other fields to save memory,
        // because we will not use other fields
        private boolean isValidMvPlan;

        public MvRewriteContext() {
            this.logicalPlan = null;
            this.outputColumns = null;
            this.refFactory = null;
            this.isValidMvPlan = false;
        }

        public MvRewriteContext(
                OptExpression logicalPlan,
                List<ColumnRefOperator> outputColumns,
                ColumnRefFactory refFactory) {
            this.logicalPlan = logicalPlan;
            this.outputColumns = outputColumns;
            this.refFactory = refFactory;
            this.isValidMvPlan = true;
        }

        public OptExpression getLogicalPlan() {
            return logicalPlan;
        }

        public List<ColumnRefOperator> getOutputColumns() {
            return outputColumns;
        }

        public ColumnRefFactory getRefFactory() {
            return refFactory;
        }

        public boolean isValidMvPlan() {
            return isValidMvPlan;
        }
    }
    // context used in mv rewrite
    // just in memory now
    // there are only reads after first-time write
    private MvRewriteContext mvRewriteContext;

    public MaterializedView() {
        super(TableType.MATERIALIZED_VIEW);
        this.tableProperty = null;
        this.state = OlapTableState.NORMAL;
        this.active = true;
    }

    public MaterializedView(long id, long dbId, String mvName, List<Column> baseSchema, KeysType keysType,
                            PartitionInfo partitionInfo, DistributionInfo defaultDistributionInfo,
                            MvRefreshScheme refreshScheme) {
        super(id, mvName, baseSchema, keysType, partitionInfo, defaultDistributionInfo,
                GlobalStateMgr.getCurrentState().getClusterId(), null, TableType.MATERIALIZED_VIEW);
        this.dbId = dbId;
        this.refreshScheme = refreshScheme;
        this.active = true;
    }

    public MvId getMvId() {
        return new MvId(getDbId(), id);
    }

    public long getDbId() {
        return dbId;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getViewDefineSql() {
        return viewDefineSql;
    }

    public void setViewDefineSql(String viewDefineSql) {
        this.viewDefineSql = viewDefineSql;
    }

    public String getSimpleDefineSql() {
        return simpleDefineSql;
    }

    public void setSimpleDefineSql(String simple) {
        this.simpleDefineSql = simple;
    }

    public List<BaseTableInfo> getBaseTableInfos() {
        return baseTableInfos;
    }

    public void setBaseTableInfos(List<BaseTableInfo> baseTableInfos) {
        this.baseTableInfos = baseTableInfos;
    }

    public void setPartitionRefTableExprs(List<Expr> partitionRefTableExprs) {
        this.partitionRefTableExprs = partitionRefTableExprs;
    }

    public List<Expr> getPartitionRefTableExprs() {
        return partitionRefTableExprs;
    }

    public MvRefreshScheme getRefreshScheme() {
        return refreshScheme;
    }

    public void setRefreshScheme(MvRefreshScheme refreshScheme) {
        this.refreshScheme = refreshScheme;
    }

    public Set<String> getUpdatedPartitionNamesOfTable(Table base) {
        return getUpdatedPartitionNamesOfTable(base, false);
    }

    public static SlotRef getPartitionSlotRef(MaterializedView materializedView) {
        List<SlotRef> slotRefs = com.clearspring.analytics.util.Lists.newArrayList();
        Expr partitionExpr = getPartitionExpr(materializedView);
        partitionExpr.collect(SlotRef.class, slotRefs);
        // if partitionExpr is FunctionCallExpr, get first SlotRef
        Preconditions.checkState(slotRefs.size() == 1);
        return slotRefs.get(0);
    }

    public static Expr getPartitionExpr(MaterializedView materializedView) {
        ExpressionRangePartitionInfo expressionRangePartitionInfo =
                ((ExpressionRangePartitionInfo) materializedView.getPartitionInfo());
        // currently, mv only supports one expression
        Preconditions.checkState(expressionRangePartitionInfo.getPartitionExprs().size() == 1);
        return materializedView.getPartitionRefTableExprs().get(0);
    }

    public Set<String> getUpdatedPartitionNamesOfOlapTable(OlapTable baseTable) {
        Map<String, BasePartitionInfo> baseTableVisibleVersionMap = getRefreshScheme()
                .getAsyncRefreshContext()
                .getBaseTableVisibleVersionMap()
                .computeIfAbsent(baseTable.getId(), k -> Maps.newHashMap());
        Set<String> result = Sets.newHashSet();
        // check whether there are partitions added and have data
        for (String partitionName : baseTable.getPartitionNames()) {
            if (!baseTableVisibleVersionMap.containsKey(partitionName)
                    && baseTable.getPartition(partitionName).getVisibleVersion() != 1) {
                result.add(partitionName);
            }
        }

        for (Map.Entry<String, BasePartitionInfo> versionEntry : baseTableVisibleVersionMap.entrySet()) {
            String basePartitionName = versionEntry.getKey();
            Partition basePartition = baseTable.getPartition(basePartitionName);
            if (basePartition == null) {
                // partitions deleted
                result.addAll(baseTable.getPartitionNames());
                return result;
            }
            BasePartitionInfo basePartitionInfo = versionEntry.getValue();
            if (basePartitionInfo == null
                    || basePartitionInfo.getId() != basePartition.getId()
                    || basePartition.getVisibleVersion() > basePartitionInfo.getVersion()) {
                result.add(basePartitionName);
            }
        }
        return result;
    }

<<<<<<< HEAD
    public Set<String> getUpdatedPartitionNamesOfExternalTable(Table baseTable)  {
=======
    private boolean isLessThanMVRewriteStaleness() {
        if (this.maxMVRewriteStaleness <= 0) {
            return false;
        }
        // Use mv's last refresh time to check whether to satisfy the `max_staleness`.
        long lastRefreshTime = refreshScheme.getLastRefreshTime();
        long currentTimestamp = System.currentTimeMillis();
        if (lastRefreshTime < currentTimestamp - this.maxMVRewriteStaleness * 1000) {
            return false;
        }
        return true;
    }

    public Map<String, BasePartitionInfo> getBaseTableRefreshInfo(BaseTableInfo baseTable) {
        return getRefreshScheme()
                .getAsyncRefreshContext()
                .getBaseTableInfoVisibleVersionMap()
                .computeIfAbsent(baseTable, k -> Maps.newHashMap());
    }

    public List<BasePartitionInfo> getBaseTableLatestPartitionInfo(Table baseTable) {
        if (baseTable.isNativeTableOrMaterializedView()) {
            return baseTable.getPartitions().stream()
                    .map(BasePartitionInfo::fromOlapTable).collect(Collectors.toList());
        }

        return MapUtils.emptyIfNull(PartitionUtil.getPartitionNameWithPartitionInfo(baseTable)).values()
                .stream().map(BasePartitionInfo::fromExternalTable).collect(Collectors.toList());
    }

    public Set<String> getUpdatedPartitionNamesOfExternalTable(Table baseTable) {
>>>>>>> 4277d9435f ([Enhancement] introduce loose query rewrite mode (#27280))
        if (!baseTable.isHiveTable()) {
            // Only support hive table now
            return null;
        }
        Set<String> result = Sets.newHashSet();
<<<<<<< HEAD
        Map<String, com.starrocks.connector.PartitionInfo> partitionNameWithPartition =
=======

        // NOTE: For query dump replay, ignore updated partition infos only to check mv can rewrite query or not.
        if (FeConstants.isReplayFromQueryDump) {
            return result;
        }

        Map<String, com.starrocks.connector.PartitionInfo> latestPartitionInfo =
>>>>>>> 4277d9435f ([Enhancement] introduce loose query rewrite mode (#27280))
                PartitionUtil.getPartitionNameWithPartitionInfo(baseTable);

        for (BaseTableInfo baseTableInfo : baseTableInfos) {
            if (!baseTableInfo.getTableIdentifier().equalsIgnoreCase(baseTable.getTableIdentifier())) {
                continue;
            }
            Map<String, BasePartitionInfo> baseTableInfoVisibleVersionMap = getBaseTableRefreshInfo(baseTableInfo);

            // check whether there are partitions added
<<<<<<< HEAD
            for (String partitionName : partitionNameWithPartition.keySet()) {
                if (!baseTableInfoVisibleVersionMap.containsKey(partitionName)) {
                    result.add(partitionName);
=======
            for (Map.Entry<String, com.starrocks.connector.PartitionInfo> entry : latestPartitionInfo.entrySet()) {
                if (!baseTableInfoVisibleVersionMap.containsKey(entry.getKey())) {
                    result.add(entry.getKey());
>>>>>>> 4277d9435f ([Enhancement] introduce loose query rewrite mode (#27280))
                }
            }

            for (Map.Entry<String, BasePartitionInfo> versionEntry : baseTableInfoVisibleVersionMap.entrySet()) {
                String basePartitionName = versionEntry.getKey();
                if (!latestPartitionInfo.containsKey(basePartitionName)) {
                    // partitions deleted
<<<<<<< HEAD
                    result.addAll(partitionNameWithPartition.keySet());
                    return result;
=======
                    return latestPartitionInfo.keySet();
>>>>>>> 4277d9435f ([Enhancement] introduce loose query rewrite mode (#27280))
                }
                long basePartitionVersion = latestPartitionInfo.get(basePartitionName).getModifiedTime();

                BasePartitionInfo basePartitionInfo = versionEntry.getValue();
                if (basePartitionInfo == null || basePartitionVersion != basePartitionInfo.getVersion()) {
                    result.add(basePartitionName);
                }
            }
        }
        return result;
    }

    public Set<String> getUpdatedPartitionNamesOfTable(Table base, boolean withMv) {
        if (base.isLocalTable()) {
            Set<String> result = Sets.newHashSet();
            OlapTable baseTable = (OlapTable) base;
            result.addAll(getUpdatedPartitionNamesOfOlapTable(baseTable));
            if (withMv && baseTable.isMaterializedView()) {
                Set<String> partitionNames = ((MaterializedView) baseTable).getPartitionNamesToRefreshForMv();
                result.addAll(partitionNames);
            }
            return result;
        } else {
            Set<String> updatePartitionNames = getUpdatedPartitionNamesOfExternalTable(base);
            Pair<Table, Column> partitionTableAndColumn = getPartitionTableAndColumn();
            if (partitionTableAndColumn == null) {
                return updatePartitionNames;
            }
            if (!base.getTableIdentifier().equals(partitionTableAndColumn.first.getTableIdentifier())) {
                return updatePartitionNames;
            }
            try {
                return PartitionUtil.getMVPartitionName(base, partitionTableAndColumn.second,
                        Lists.newArrayList(updatePartitionNames));
            } catch (AnalysisException e) {
                LOG.warn("Mv {}'s base table {} get partition name fail", name, base.name, e);
                return null;
            }
        }
    }

    @Override
    public TTableDescriptor toThrift(List<ReferencedPartitionInfo> partitions) {
        return new TTableDescriptor(id, TTableType.MATERIALIZED_VIEW,
                fullSchema.size(), 0, getName(), "");
    }

    @Override
    public MaterializedView selectiveCopy(Collection<String> reservedPartitions, boolean resetState,
                                          MaterializedIndex.IndexExtState extState) {
        MaterializedView copied = DeepCopy.copyWithGson(this, MaterializedView.class);
        if (copied == null) {
            LOG.warn("failed to copy materialized view: " + getName());
            return null;
        }
        return ((MaterializedView) selectiveCopyInternal(copied, reservedPartitions, resetState, extState));
    }

    @Override
    public void write(DataOutput out) throws IOException {
        // write type first
        Text.writeString(out, type.name());
        Text.writeString(out, GsonUtils.GSON.toJson(this));
    }

    @Override
    public void onCreate() {
        Database db = GlobalStateMgr.getCurrentState().getDb(dbId);
        if (db == null) {
            LOG.warn("db:{} do not exist. materialized view id:{} name:{} should not exist", dbId, id, name);
            active = false;
            return;
        }
        if (baseTableInfos == null) {
            baseTableInfos = Lists.newArrayList();
            if (baseTableIds != null) {
                // for compatibility
                for (long tableId : baseTableIds) {
                    baseTableInfos.add(new BaseTableInfo(dbId, db.getFullName(), tableId));
                }
            } else {
                active = false;
                return;
            }
        }

        for (BaseTableInfo baseTableInfo : baseTableInfos) {
            // Do not set the active when table is null, it would be checked in MVActiveChecker
            Table table = baseTableInfo.getTable();
            if (table != null) {
                if (table instanceof MaterializedView && !((MaterializedView) table).isActive()) {
                    LOG.warn("tableName :{} is invalid. set materialized view:{} to invalid",
                            baseTableInfo.getTableName(), id);
                    active = false;
                    continue;
                }
                MvId mvId = new MvId(db.getId(), id);
                table.addRelatedMaterializedView(mvId);

                if (!table.isLocalTable()) {
                    GlobalStateMgr.getCurrentState().getConnectorTblMetaInfoMgr().addConnectorTableInfo(
                            baseTableInfo.getCatalogName(), baseTableInfo.getDbName(),
                            baseTableInfo.getTableIdentifier(),
                            ConnectorTableInfo.builder().setRelatedMaterializedViews(
                                    Sets.newHashSet(mvId)).build()
                    );
                }
            }
        }
        analyzePartitionInfo();
    }

    private void analyzePartitionInfo() {
        Database db = GlobalStateMgr.getCurrentState().getDb(dbId);

        if (partitionInfo instanceof SinglePartitionInfo) {
            return;
        }
        // analyze expression, because it converts to sql for serialize
        ConnectContext connectContext = new ConnectContext();
        connectContext.setDatabase(db.getFullName());
        // set privilege
        connectContext.setQualifiedUser(Auth.ROOT_USER);
        connectContext.setCurrentUserIdentity(UserIdentity.ROOT);
        ExpressionRangePartitionInfo expressionRangePartitionInfo = (ExpressionRangePartitionInfo) partitionInfo;
        // currently, mv only supports one expression
<<<<<<< HEAD
        Expr partitionExpr = expressionRangePartitionInfo.getPartitionExprs().get(0);
        // for Partition slot ref, the SlotDescriptor is not serialized, so should recover it here.
        // the SlotDescriptor is used by toThrift, which influences the execution process.
        List<SlotRef> slotRefs = Lists.newArrayList();
        partitionExpr.collect(SlotRef.class, slotRefs);
        Preconditions.checkState(slotRefs.size() == 1);
        if (slotRefs.get(0).getSlotDescriptorWithoutCheck() == null) {
            for (int i = 0; i < fullSchema.size(); i++) {
                Column column = fullSchema.get(i);
                if (column.getName().equalsIgnoreCase(slotRefs.get(0).getColumnName())) {
                    SlotDescriptor slotDescriptor =
                            new SlotDescriptor(new SlotId(i), column.getName(), column.getType(), column.isAllowNull());
                    slotRefs.get(0).setDesc(slotDescriptor);
=======
        if (partitionInfo instanceof ExpressionRangePartitionInfo) {
            ExpressionRangePartitionInfo expressionRangePartitionInfo = (ExpressionRangePartitionInfo) partitionInfo;
            Expr partitionExpr = expressionRangePartitionInfo.getPartitionExprs().get(0);
            // for Partition slot ref, the SlotDescriptor is not serialized, so should recover it here.
            // the SlotDescriptor is used by toThrift, which influences the execution process.
            List<SlotRef> slotRefs = Lists.newArrayList();
            partitionExpr.collect(SlotRef.class, slotRefs);
            Preconditions.checkState(slotRefs.size() == 1);
            if (slotRefs.get(0).getSlotDescriptorWithoutCheck() == null) {
                for (int i = 0; i < fullSchema.size(); i++) {
                    Column column = fullSchema.get(i);
                    if (column.getName().equalsIgnoreCase(slotRefs.get(0).getColumnName())) {
                        SlotDescriptor slotDescriptor =
                                new SlotDescriptor(new SlotId(i), column.getName(), column.getType(),
                                        column.isAllowNull());
                        slotRefs.get(0).setDesc(slotDescriptor);
                    }
>>>>>>> 4277d9435f ([Enhancement] introduce loose query rewrite mode (#27280))
                }
            }
        }

        ExpressionAnalyzer.analyzeExpression(partitionExpr, new AnalyzeState(),
                new Scope(RelationId.anonymous(),
                        new RelationFields(this.getBaseSchema().stream()
                                .map(col -> new Field(col.getName(), col.getType(),
                                        new TableName(db.getFullName(), this.name), null))
                                .collect(Collectors.toList()))), connectContext);
    }

    public static MaterializedView read(DataInput in) throws IOException {
        String json = Text.readString(in);
        return GsonUtils.GSON.fromJson(json, MaterializedView.class);
    }

    /**
     * Refresh the materialized view if the following conditions are met:
     * 1. Refresh type of materialized view is ASYNC
     * 2. timeunit and step not set for AsyncRefreshContext
     *
     * @return
     */
    public boolean isLoadTriggeredRefresh() {
        AsyncRefreshContext asyncRefreshContext = this.refreshScheme.asyncRefreshContext;
        return this.refreshScheme.getType() == MaterializedView.RefreshType.ASYNC &&
                asyncRefreshContext.step == 0 && null == asyncRefreshContext.timeUnit;
    }

<<<<<<< HEAD
    public boolean isForceExternalTableQueryRewrite() {
=======
    public TableProperty.QueryRewriteConsistencyMode getForceExternalTableQueryRewrite() {
>>>>>>> 4277d9435f ([Enhancement] introduce loose query rewrite mode (#27280))
        return tableProperty.getForceExternalTableQueryRewrite();
    }

    public boolean shouldTriggeredRefreshBy(String dbName, String tableName) {
        if (!isLoadTriggeredRefresh()) {
            return false;
        }
        TableProperty tableProperty = getTableProperty();
        if (tableProperty == null) {
            return true;
        }
        List<TableName> excludedTriggerTables =  tableProperty.getExcludedTriggerTables();
        if (excludedTriggerTables == null) {
            return true;
        }
        for (TableName tables : excludedTriggerTables) {
            if (tables.getDb() == null) {
                if (tables.getTbl().equals(tableName)) {
                    return false;
                }
            } else {
                if (tables.getDb().equals(dbName) && tables.getTbl().equals(tableName)) {
                    return false;
                }
            }
        }
        return true;
    }

    public String getMaterializedViewDdlStmt(boolean simple) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE MATERIALIZED VIEW `").append(this.getName()).append("`");
        if (!Strings.isNullOrEmpty(this.getComment())) {
            sb.append("\nCOMMENT \"").append(this.getDisplayComment()).append("\"");
        }

        // partition
        PartitionInfo partitionInfo = this.getPartitionInfo();
        if (!(partitionInfo instanceof SinglePartitionInfo)) {
            sb.append("\n").append(partitionInfo.toSql(this, null));
        }

        // distribution
        DistributionInfo distributionInfo = this.getDefaultDistributionInfo();
        sb.append("\n").append(distributionInfo.toSql());

        // refresh scheme
        MvRefreshScheme refreshScheme = this.getRefreshScheme();
        if (refreshScheme == null) {
            sb.append("\nREFRESH ").append("UNKNOWN");
        } else {
            sb.append("\nREFRESH ").append(refreshScheme.getType());
        }
        if (refreshScheme != null && refreshScheme.getType() == RefreshType.ASYNC) {
            AsyncRefreshContext asyncRefreshContext = refreshScheme.getAsyncRefreshContext();
            if (asyncRefreshContext.isDefineStartTime()) {
                sb.append(" START(\"").append(Utils.getDatetimeFromLong(asyncRefreshContext.getStartTime())
                        .format(DateUtils.DATE_TIME_FORMATTER))
                        .append("\")");
            }
            if (asyncRefreshContext.getTimeUnit() != null) {
                sb.append(" EVERY(INTERVAL ").append(asyncRefreshContext.getStep()).append(" ")
                        .append(asyncRefreshContext.getTimeUnit()).append(")");
            }
        }

        // properties
        sb.append("\nPROPERTIES (\n");

        // replicationNum
        Short replicationNum = this.getDefaultReplicationNum();
        sb.append("\"").append(PropertyAnalyzer.PROPERTIES_REPLICATION_NUM).append("\" = \"");
        sb.append(replicationNum).append("\"");

        // storageMedium
        String storageMedium = this.getStorageMedium();
        sb.append(StatsConstants.TABLE_PROPERTY_SEPARATOR).append(PropertyAnalyzer.PROPERTIES_STORAGE_MEDIUM)
                .append("\" = \"");
        sb.append(storageMedium).append("\"");

        // storageCooldownTime
        Map<String, String> properties = this.getTableProperty().getProperties();
        if (properties.containsKey(PropertyAnalyzer.PROPERTIES_STORAGE_COOLDOWN_TIME)) {
            sb.append(StatsConstants.TABLE_PROPERTY_SEPARATOR).append(PropertyAnalyzer.PROPERTIES_STORAGE_COOLDOWN_TIME)
                    .append("\" = \"");
            sb.append(TimeUtils.longToTimeString(
                    Long.parseLong(properties.get(PropertyAnalyzer.PROPERTIES_STORAGE_COOLDOWN_TIME)))).append("\"");
        }

        // partition TTL
        if (properties.containsKey(PropertyAnalyzer.PROPERTIES_PARTITION_TTL_NUMBER)) {
            sb.append(StatsConstants.TABLE_PROPERTY_SEPARATOR).append(PropertyAnalyzer.PROPERTIES_PARTITION_TTL_NUMBER)
                    .append("\" = \"");
            sb.append(properties.get(PropertyAnalyzer.PROPERTIES_PARTITION_TTL_NUMBER)).append("\"");
        }

        // auto refresh partitions limit
        if (properties.containsKey(PropertyAnalyzer.PROPERTIES_AUTO_REFRESH_PARTITIONS_LIMIT)) {
            sb.append(StatsConstants.TABLE_PROPERTY_SEPARATOR)
                    .append(PropertyAnalyzer.PROPERTIES_AUTO_REFRESH_PARTITIONS_LIMIT)
                    .append("\" = \"");
            sb.append(properties.get(PropertyAnalyzer.PROPERTIES_AUTO_REFRESH_PARTITIONS_LIMIT)).append("\"");
        }

        // partition refresh number
        if (properties.containsKey(PropertyAnalyzer.PROPERTIES_PARTITION_REFRESH_NUMBER)) {
            sb.append(StatsConstants.TABLE_PROPERTY_SEPARATOR)
                    .append(PropertyAnalyzer.PROPERTIES_PARTITION_REFRESH_NUMBER)
                    .append("\" = \"");
            sb.append(properties.get(PropertyAnalyzer.PROPERTIES_PARTITION_REFRESH_NUMBER)).append("\"");
        }

        // excluded trigger tables
        if (properties.containsKey(PropertyAnalyzer.PROPERTIES_EXCLUDED_TRIGGER_TABLES)) {
            sb.append(StatsConstants.TABLE_PROPERTY_SEPARATOR)
                    .append(PropertyAnalyzer.PROPERTIES_EXCLUDED_TRIGGER_TABLES)
                    .append("\" = \"");
            sb.append(properties.get(PropertyAnalyzer.PROPERTIES_EXCLUDED_TRIGGER_TABLES)).append("\"");
        }

        // force_external_table_query_rewrite
        if (properties.containsKey(PropertyAnalyzer.PROPERTIES_FORCE_EXTERNAL_TABLE_QUERY_REWRITE)) {
            sb.append(StatsConstants.TABLE_PROPERTY_SEPARATOR).append(
                    PropertyAnalyzer.PROPERTIES_FORCE_EXTERNAL_TABLE_QUERY_REWRITE).append("\" = \"");
            sb.append(properties.get(PropertyAnalyzer.PROPERTIES_FORCE_EXTERNAL_TABLE_QUERY_REWRITE)).append("\"");
        }

        // unique constraints
        if (properties.containsKey(PropertyAnalyzer.PROPERTIES_UNIQUE_CONSTRAINT)) {
            sb.append(StatsConstants.TABLE_PROPERTY_SEPARATOR).append(PropertyAnalyzer.PROPERTIES_UNIQUE_CONSTRAINT)
                    .append("\" = \"");
            sb.append(properties.get(PropertyAnalyzer.PROPERTIES_UNIQUE_CONSTRAINT)).append("\"");
        }

        // foreign keys constraints
        if (properties.containsKey(PropertyAnalyzer.PROPERTIES_FOREIGN_KEY_CONSTRAINT)) {
            sb.append(StatsConstants.TABLE_PROPERTY_SEPARATOR)
                    .append(PropertyAnalyzer.PROPERTIES_FOREIGN_KEY_CONSTRAINT)
                    .append("\" = \"");
            sb.append(ForeignKeyConstraint.getShowCreateTableConstraintDesc(getForeignKeyConstraints()))
                    .append("\"");
        }

        sb.append("\n)");
        String define = this.getSimpleDefineSql();
        if (StringUtils.isEmpty(define) || !simple) {
            define = this.getViewDefineSql();
        }
        sb.append("\nAS ").append(define);
        sb.append(";");
        return sb.toString();
    }

    private static final ImmutableSet<String> NEED_SHOW_PROPS;

    static {
        NEED_SHOW_PROPS = new ImmutableSet.Builder<String>()
        .add(PropertyAnalyzer.PROPERTIES_STORAGE_COOLDOWN_TIME)
        .add(PropertyAnalyzer.PROPERTIES_PARTITION_TTL_NUMBER)
        .add(PropertyAnalyzer.PROPERTIES_AUTO_REFRESH_PARTITIONS_LIMIT)
        .add(PropertyAnalyzer.PROPERTIES_PARTITION_REFRESH_NUMBER)
        .add(PropertyAnalyzer.PROPERTIES_EXCLUDED_TRIGGER_TABLES)
        .build();
    }

    public Map<String, String> getMaterializedViewPropMap() {

        Map<String, String> propsMap = new HashMap<>();
        // replicationNum
        Short replicationNum = this.getDefaultReplicationNum();
        propsMap.put(PropertyAnalyzer.PROPERTIES_REPLICATION_NUM, String.valueOf(replicationNum));

        // storageMedium
        String storageMedium = this.getStorageMedium();
        propsMap.put(PropertyAnalyzer.PROPERTIES_STORAGE_MEDIUM, storageMedium);
        Map<String, String> properties = this.getTableProperty().getProperties();

        // NEED_SHOW_PROPS
        NEED_SHOW_PROPS.forEach(prop -> {
            if (properties.containsKey(prop)) {
                if (prop.equals(PropertyAnalyzer.PROPERTIES_STORAGE_COOLDOWN_TIME)) {
                    propsMap.put(prop, TimeUtils.longToTimeString(
                            Long.parseLong(properties.get(prop))));
                } else {
                    propsMap.put(prop, properties.get(prop));
                }
            }
        });
        return propsMap;
    }

    public boolean containsBaseTable(TableName tableName) {
        for (BaseTableInfo baseTableInfo : baseTableInfos) {
            if (tableName.getDb() == null) {
                if (tableName.getTbl().equals(baseTableInfo.getTableName())) {
                    return true;
                }
            } else {
                if (tableName.getTbl().equals(baseTableInfo.getTableName()) &&
                        tableName.getDb().equals(baseTableInfo.getDbName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean supportPartialPartitionQueryRewriteForExternalTable(Table table) {
        return table.isHiveTable();
    }

    public Set<String> getPartitionNamesToRefreshForMv() {
        PartitionInfo partitionInfo = getPartitionInfo();
<<<<<<< HEAD

        boolean forceExternalTableQueryRewrite = isForceExternalTableQueryRewrite();
=======
        TableProperty.QueryRewriteConsistencyMode externalTableRewriteMode = getForceExternalTableQueryRewrite();
        TableProperty.QueryRewriteConsistencyMode olapTableRewriteMode = tableProperty.getOlapTableQueryRewrite();
>>>>>>> 4277d9435f ([Enhancement] introduce loose query rewrite mode (#27280))
        if (partitionInfo instanceof SinglePartitionInfo) {
            // for non-partitioned materialized view
            for (BaseTableInfo tableInfo : baseTableInfos) {
                Table table = tableInfo.getTable();

<<<<<<< HEAD
                // we can not judge whether mv based on external table is update-to-date,
                // because we do not know that any changes in external table.
                if (!table.isNativeTable()) {
                    if (forceExternalTableQueryRewrite) {
                        if (!supportPartialPartitionQueryRewriteForExternalTable(table)) {
                            // if forceExternalTableQueryRewrite set to true, no partition need to refresh for mv.
                            continue;
=======
                if (table.isView()) {
                    // freshness of view should be ignored
                    continue;
                }
                // we can not judge whether mv based on external table is update-to-date,
                // because we do not know that any changes in external table.
                if (!table.isNativeTableOrMaterializedView()) {
                    switch (externalTableRewriteMode) {
                        case DISABLE:
                            return getPartitionNames();
                        case LOOSE: {
                            return Sets.newHashSet();
>>>>>>> 4277d9435f ([Enhancement] introduce loose query rewrite mode (#27280))
                        }
                        case CHECKED:
                            if (!supportPartialPartitionQueryRewriteForExternalTable(table)) {
                                continue;
                            }
                            break;
                        default:
                            Preconditions.checkState(false, "unknown force_external_table_rewrite");
                    }
                }
<<<<<<< HEAD
=======
                if (table.isNativeTableOrMaterializedView()) {
                    switch (olapTableRewriteMode) {
                        case DISABLE:
                            return getPartitionNames();
                        case LOOSE:
                            return Sets.newHashSet();
                        case CHECKED:
                        default:
                            break;
                    }
                }

                // Check the partition consistency
>>>>>>> 4277d9435f ([Enhancement] introduce loose query rewrite mode (#27280))
                Set<String> partitionNames = getUpdatedPartitionNamesOfTable(table, true);
                if (CollectionUtils.isNotEmpty(partitionNames)) {
                    return getPartitionNames();
                }
            }
        } else if (partitionInfo instanceof ExpressionRangePartitionInfo) {
            // partitions to refresh
            // 1. dropped partitions
            // 2. newly added partitions
            // 3. partitions loaded with new data
            return getPartitionNamesToRefreshForPartitionedMv();
        } else {
            throw UnsupportedException.unsupportedException("unsupported partition info type:"
                    + partitionInfo.getClass().getName());
        }
        return Sets.newHashSet();
    }

    private Set<String> getPartitionNamesToRefreshForPartitionedMv() {
        Expr partitionExpr = getPartitionRefTableExprs().get(0);
        Pair<Table, Column> partitionInfo = getPartitionTableAndColumn();
        // if non-partition-by table has changed, should refresh all mv partitions
        if (partitionInfo == null) {
            // mark it inactive
            setActive(false);
            LOG.warn("mark mv:{} inactive for get partition info failed", name);
            throw new RuntimeException(String.format("getting partition info failed for mv: %s", name));
        }
        Table partitionTable = partitionInfo.first;
        TableProperty.QueryRewriteConsistencyMode externalTableRewriteMode = getForceExternalTableQueryRewrite();
        TableProperty.QueryRewriteConsistencyMode olapTableRewriteMode = tableProperty.getOlapTableQueryRewrite();
        for (BaseTableInfo tableInfo : baseTableInfos) {
            Table table = tableInfo.getTable();
<<<<<<< HEAD
            if (!table.isNativeTable()) {
                if (forceExternalTableQueryRewrite) {
                    // if forceExternalTableQueryRewrite set to true, no partition need to refresh for mv.
                    if (!supportPartialPartitionQueryRewriteForExternalTable(table)) {
=======
            if (table.isView()) {
                continue;
            }
            if (!table.isNativeTableOrMaterializedView()) {
                switch (externalTableRewriteMode) {
                    case DISABLE:
                        return getPartitionNames();
                    case LOOSE:
>>>>>>> 4277d9435f ([Enhancement] introduce loose query rewrite mode (#27280))
                        return Sets.newHashSet();
                    case CHECKED: {
                        if (!supportPartialPartitionQueryRewriteForExternalTable(table)) {
                            continue;
                        }
                        break;
                    }
                    default:
                        Preconditions.checkState(false, "unknown force_external_table_query_rewrite");
                }
            }
            if (table.isNativeTableOrMaterializedView()) {
                switch (olapTableRewriteMode) {
                    case DISABLE:
                        return getPartitionNames();
                    case LOOSE:
                        return Sets.newHashSet();
                    case CHECKED:
                    default:
                        break;
                }
            }

            if (table.getTableIdentifier().equals(partitionTable.getTableIdentifier())) {
                continue;
            }
            Set<String> partitionNames = getUpdatedPartitionNamesOfTable(table, true);
            if (CollectionUtils.isNotEmpty(partitionNames)) {
                return getPartitionNames();
            }
        }
        // check partition-by table
        Set<String> needRefreshMvPartitionNames = Sets.newHashSet();
        Map<String, Range<PartitionKey>> basePartitionMap;
        try {
            basePartitionMap = PartitionUtil.getPartitionRange(partitionTable,
                    partitionInfo.second);
        } catch (UserException e) {
            LOG.warn("Materialized view compute partition difference with base table failed.", e);
            return getPartitionNames();
        }
        Map<String, Range<PartitionKey>> mvPartitionMap = getRangePartitionMap();
        PartitionDiff partitionDiff = getPartitionDiff(partitionExpr, partitionInfo.second,
                basePartitionMap, mvPartitionMap);
        needRefreshMvPartitionNames.addAll(partitionDiff.getDeletes().keySet());
        for (String deleted : partitionDiff.getDeletes().keySet()) {
            mvPartitionMap.remove(deleted);
        }
        needRefreshMvPartitionNames.addAll(partitionDiff.getAdds().keySet());
        mvPartitionMap.putAll(partitionDiff.getAdds());

        Map<String, Set<String>> baseToMvNameRef = SyncPartitionUtils
                .generatePartitionRefMap(basePartitionMap, mvPartitionMap);
        Map<String, Set<String>> mvToBaseNameRef = SyncPartitionUtils
                .generatePartitionRefMap(mvPartitionMap, basePartitionMap);

        Set<String> baseChangedPartitionNames = getUpdatedPartitionNamesOfTable(partitionTable, true);
        if (baseChangedPartitionNames == null) {
            return mvToBaseNameRef.keySet();
        }
        if (partitionExpr instanceof SlotRef) {
            for (String basePartitionName : baseChangedPartitionNames) {
                needRefreshMvPartitionNames.addAll(baseToMvNameRef.get(basePartitionName));
            }
        } else if (partitionExpr instanceof FunctionCallExpr) {
            for (String baseChangedPartitionName : baseChangedPartitionNames) {
                needRefreshMvPartitionNames.addAll(baseToMvNameRef.get(baseChangedPartitionName));
            }
            // because the relation of partitions between materialized view and base partition table is n : m,
            // should calculate the candidate partitions recursively.
            SyncPartitionUtils.calcPotentialRefreshPartition(needRefreshMvPartitionNames, baseChangedPartitionNames,
                    baseToMvNameRef, mvToBaseNameRef);
        }
        return needRefreshMvPartitionNames;
    }

    private PartitionDiff getPartitionDiff(Expr partitionExpr, Column partitionColumn,
                                           Map<String, Range<PartitionKey>> basePartitionMap,
                                           Map<String, Range<PartitionKey>> mvPartitionMap) {
        if (partitionExpr instanceof SlotRef) {
            return SyncPartitionUtils.calcSyncSamePartition(basePartitionMap, mvPartitionMap);
        } else if (partitionExpr instanceof FunctionCallExpr) {
            FunctionCallExpr functionCallExpr = (FunctionCallExpr) partitionExpr;
            String granularity = ((StringLiteral) functionCallExpr.getChild(0)).getValue().toLowerCase();
            return SyncPartitionUtils.calcSyncRollupPartition(basePartitionMap, mvPartitionMap,
                    granularity, partitionColumn.getPrimitiveType());
        } else {
            throw UnsupportedException.unsupportedException("unsupported partition expr:" + partitionExpr);
        }
    }

    public Pair<Table, Column> getPartitionTableAndColumn() {
        if (!(partitionInfo instanceof ExpressionRangePartitionInfo)) {
            return null;
        }
        Expr partitionExpr = getPartitionRefTableExprs().get(0);
        List<SlotRef> slotRefs = Lists.newArrayList();
        partitionExpr.collect(SlotRef.class, slotRefs);
        Preconditions.checkState(slotRefs.size() == 1);
        SlotRef partitionSlotRef = slotRefs.get(0);
        for (BaseTableInfo baseTableInfo : baseTableInfos) {
            Table table = baseTableInfo.getTable();
            if (partitionSlotRef.getTblNameWithoutAnalyzed().getTbl().equals(baseTableInfo.getTableName())) {
                return Pair.create(table, table.getColumn(partitionSlotRef.getColumnName()));
            }
        }
        String baseTableNames = baseTableInfos.stream()
                .map(tableInfo -> tableInfo.getTable().getName()).collect(Collectors.joining(","));
        throw new RuntimeException(
                String.format("can not find partition info for mv:%s on base tables:%s", name, baseTableNames));
    }

    public MvRewriteContext getPlanContext() {
        return mvRewriteContext;
    }

<<<<<<< HEAD
    public void setPlanContext(MvRewriteContext mvRewriteContext) {
        this.mvRewriteContext = mvRewriteContext;
=======
    public void setMaintenancePlan(ExecPlan maintenancePlan) {
        this.maintenancePlan = maintenancePlan;
    }

    public MVRewriteContextCache getPlanContext() {
        return mvRewriteContextCache;
    }

    public void setPlanContext(MVRewriteContextCache mvRewriteContextCache) {
        this.mvRewriteContextCache = mvRewriteContextCache;
    }

    /**
     * Infer the distribution info based on tables and MV query.
     * Currently is max{bucket_num of base_table}
     * TODO: infer the bucket number according to MV pattern and cardinality
     */
    @Override
    public void inferDistribution(DistributionInfo info) throws DdlException {
        if (info.getBucketNum() == 0) {
            int inferredBucketNum = 0;
            for (BaseTableInfo base : getBaseTableInfos()) {
                if (base.getTable().isNativeTableOrMaterializedView()) {
                    OlapTable olapTable = (OlapTable) base.getTable();
                    DistributionInfo dist = olapTable.getDefaultDistributionInfo();
                    inferredBucketNum = Math.max(inferredBucketNum, dist.getBucketNum());
                }
            }
            if (inferredBucketNum == 0) {
                inferredBucketNum = CatalogUtils.calBucketNumAccordingToBackends();
            }
            info.setBucketNum(inferredBucketNum);
        }
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = super.getProperties();
        // For materialized view, add into session variables into properties.
        if (super.getTableProperty() != null && super.getTableProperty().getProperties() != null) {
            for (Map.Entry<String, String> entry : super.getTableProperty().getProperties().entrySet()) {
                if (entry.getKey().startsWith(PropertyAnalyzer.PROPERTIES_MATERIALIZED_VIEW_SESSION_PREFIX)) {
                    String varKey = entry.getKey().substring(
                            PropertyAnalyzer.PROPERTIES_MATERIALIZED_VIEW_SESSION_PREFIX.length());
                    properties.put(varKey, entry.getValue());
                }
            }
        }
        return properties;
    }

    @Override
    public void gsonPreProcess() throws IOException {
        this.serializedPartitionRefTableExprs = new ArrayList<>();
        if (partitionRefTableExprs != null) {
            for (Expr partitionExpr : partitionRefTableExprs) {
                if (partitionExpr != null) {
                    serializedPartitionRefTableExprs.add(
                            new GsonUtils.ExpressionSerializedObject(partitionExpr.toSql()));
                }
            }
        }
    }

    @Override
    public void gsonPostProcess() throws IOException {
        super.gsonPostProcess();
        partitionRefTableExprs = new ArrayList<>();
        if (serializedPartitionRefTableExprs != null) {
            for (GsonUtils.ExpressionSerializedObject expressionSql : serializedPartitionRefTableExprs) {
                if (expressionSql != null) {
                    partitionRefTableExprs.add(
                            SqlParser.parseSqlToExpr(expressionSql.expressionSql, SqlModeHelper.MODE_DEFAULT));
                }
            }
        }
>>>>>>> 4277d9435f ([Enhancement] introduce loose query rewrite mode (#27280))
    }
}
