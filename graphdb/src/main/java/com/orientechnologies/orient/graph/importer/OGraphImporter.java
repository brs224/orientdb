/*
 *
 *  *  Copyright 2014 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientechnologies.com
 *
 */
package com.orientechnologies.orient.graph.importer;

import com.orientechnologies.common.io.OIOUtils;
import com.orientechnologies.common.listener.OProgressListener;
import com.orientechnologies.common.util.OPair;
import com.orientechnologies.common.util.OTriple;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.config.OStorageEntryConfiguration;
import com.orientechnologies.orient.core.db.ODatabase;
import com.orientechnologies.orient.core.index.OIndex;
import com.orientechnologies.orient.core.index.hashindex.local.OMurmurHash3HashFunction;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OProperty;
import com.orientechnologies.orient.core.metadata.schema.OSchema;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.serialization.serializer.binary.impl.index.OSimpleKeySerializer;
import com.tinkerpop.blueprints.impls.orient.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.Math.abs;

/**
 * API to ingest a graph at the maximum speed possible.
 *
 * @since 2.2.12
 * @author Luca Garulli (l.garulli-(at)-orientdb.com)
 */
public class OGraphImporter {
  private final String                                   userName;
  private final String                                   dbUrl;
  private final String                                   password;
  private int                                            parallel               = Runtime.getRuntime().availableProcessors();
  private Boolean                                        useLightWeightEdges    = null;
  private boolean                                        transactional          = true;

  private Map<String, OPair<String, OType>>              vertexClassesUsed      = new HashMap<String, OPair<String, OType>>();
  private List<OTriple<String, String, String>>          edgeClassesUsed        = new ArrayList<OTriple<String, String, String>>();

  private int                                            batchSize              = 10;
  private int                                            queueSize              = 1000;
  private int                                            maxRetry               = 10;

  private OrientGraphFactory                             factory;
  private OrientGraphNoTx                                baseGraph;
  private Map<String, OImporterWorkerThread>             threads                = new HashMap<String, OImporterWorkerThread>();
  private Map<String, Lock>                              locks                  = new HashMap<String, Lock>();
  private Map<String, String>                            vertexIndexNames       = new HashMap<String, String>();

  private Map<String, ConcurrentHashMap<Object, String>> pendingVertexCreation  = new HashMap<String, ConcurrentHashMap<Object, String>>();
  private TimerTask                                      progressTask;
  private TimerTask                                      postponeTask;
  private int                                            verboseLevel           = 1;
  private AtomicLong                                     conflicts              = new AtomicLong(0);
  private AtomicLong                                     forcedCommits          = new AtomicLong(0);
  private AtomicLong                                     totalEdges             = new AtomicLong(0);
  private long                                           startTime;
  private int                                            totalEstimatedVertices = -1;
  private int                                            totalEstimatedEdges    = -1;
  private long                                           dumpStatsEvery         = 2000;
  private boolean                                        lightweightEdges       = false;
  private int                                            backPressureThreshold  = 100;

  private final ConcurrentLinkedQueue<OOperation>        postponeExecution      = new ConcurrentLinkedQueue<OOperation>();
  private int                                            maxAttemptsToFlushTransaction;

  private long                                           lastTotalVertices      = 0;
  private long                                           lastTotalEdges         = 0;
  private long                                           lastConflicts          = 0;
  private long                                           lastForcedCommits      = 0;
  private long                                           lastRetries            = 0;
  private long                                           lastRecordsLocked      = 0;
  private long                                           lastLocks              = 0;
  private long                                           lastOpsInQueues        = 0;
  private long                                           lastPostponed          = 0;

  /**
   * Creates a new batch insert procedure by using admin user. It's intended to be used only for a single batch cycle (begin,
   * create..., end)
   *
   * @param iDbURL
   *          db connection URL (plocal:/your/db/path)
   */
  public OGraphImporter(final String iDbURL) {
    this.dbUrl = iDbURL;
    this.userName = "admin";
    this.password = "admin";
  }

  /**
   * Creates a new batch insert procedure. It's intended to be used only for a single batch cycle (begin, create..., end)
   *
   * @param iDbURL
   *          db connection URL (plocal:/your/db/path)
   * @param iUserName
   *          db user name (use admin for new db)
   * @param iPassword
   *          db password (use admin for new db)
   */
  public OGraphImporter(final String iDbURL, final String iUserName, final String iPassword) {
    this.dbUrl = iDbURL;
    this.userName = iUserName;
    this.password = iPassword;
  }

  /**
   * Creates the database (if it does not exist) and initializes batch operations. Call this once, before starting to create
   * vertices and edges.
   *
   */
  public void begin() {
    factory = new OrientGraphFactory(dbUrl, userName, password, false);
    factory.setUseLightweightEdges(lightweightEdges);

    baseGraph = factory.getNoTx();

    if (this.useLightWeightEdges == null) {
      final List<OStorageEntryConfiguration> custom = (List<OStorageEntryConfiguration>) baseGraph.getRawGraph()
          .get(ODatabase.ATTRIBUTES.CUSTOM);
      for (OStorageEntryConfiguration c : custom) {
        if (c.name.equalsIgnoreCase("useLightweightEdges")) {
          this.useLightWeightEdges = Boolean.parseBoolean(c.value);
          break;
        }
      }
      if (this.useLightWeightEdges == null) {
        this.useLightWeightEdges = true;
      }
    }
    createBaseSchema(baseGraph);

    // CREATE ALL THE NEEDED COMBINATION OF THREADS FOR V-[E]-V
    for (OTriple<String, String, String> edgeClass : edgeClassesUsed) {
      final String edgeClassName = edgeClass.getKey();
      final String sourceClassName = edgeClass.getValue().getKey();
      final String destinationClassName = edgeClass.getValue().getValue();

      for (int sourceClusterIndex = 0; sourceClusterIndex < parallel; ++sourceClusterIndex) {
        for (int destinationClusterIndex = 0; destinationClusterIndex < parallel; ++destinationClusterIndex) {
          final String workerId = sourceClassName + "_" + sourceClusterIndex + "-[" + edgeClassName + "]-" + destinationClassName
              + "_" + destinationClusterIndex;

          final OImporterWorkerThread t = new OImporterWorkerThread(workerId, this, sourceClassName, sourceClusterIndex,
              destinationClassName, destinationClusterIndex, edgeClassName);
          t.start();

          threads.put(workerId, t);
        }
      }
    }

    for (Map.Entry<String, OPair<String, OType>> vertexClass : vertexClassesUsed.entrySet()) {
      for (int vertexClusterIndex = 0; vertexClusterIndex < parallel; ++vertexClusterIndex) {
        final String workerId = vertexClass.getKey() + "_" + vertexClusterIndex;
        final OImporterWorkerThread t = new OImporterWorkerThread(workerId, this, vertexClass.getKey(), vertexClusterIndex);
        t.start();
        threads.put(workerId, t);
      }
    }

    // CREATE ONE LOCK PER CLUSTER ONLY FOR THE VERTEX CLASSES USED
    for (Map.Entry<String, OPair<String, OType>> entry : vertexClassesUsed.entrySet()) {
      final String className = entry.getKey();
      final int[] clusterIds = baseGraph.getRawGraph().getMetadata().getSchema().getClass(className).getClusterIds();

      for (int clusterId : clusterIds) {
        locks.put(baseGraph.getRawGraph().getClusterNameById(clusterId), new ReentrantLock());
      }
    }

    // CREATE ONE LOCK PER CLUSTER ONLY FOR THE EDGE CLASSES USED
    for (OTriple<String, String, String> edgeClass : edgeClassesUsed) {
      final int[] clusterIds = baseGraph.getRawGraph().getMetadata().getSchema().getClass(edgeClass.getKey()).getClusterIds();
      for (int clusterId : clusterIds) {
        locks.put(baseGraph.getRawGraph().getClusterNameById(clusterId), new ReentrantLock());
      }
    }

    baseGraph.makeActive();

    if (verboseLevel > 0) {
      progressTask = new TimerTask() {
        @Override
        public void run() {
          dumpStatus(true);
        }
      };
      Orient.instance().scheduleTask(progressTask, dumpStatsEvery, dumpStatsEvery);
    }

    postponeTask = new TimerTask() {

      @Override
      public void run() {
        executePostponedOperations(-1);
      }

    };

    Orient.instance().scheduleTask(postponeTask, 5000, 5000);

    if (verboseLevel > 0)
      System.out.println("GRAPH IMPORTER STARTED on " + new Date());

    startTime = System.currentTimeMillis();
  }

  protected boolean executePostponedOperations(final int maxOperations) {
    for (int i = 0; i < maxOperations; ++i) {
      OOperation operation = null;
      try {
        operation = postponeExecution.poll();
        if (operation == null)
          // QUEUE EMPTY, WAIT FOR THE NEXT RUN
          return false;

        if (!operation.checkForExecution(this))
          // SKIP IT, POSTPONE THE OPERATION AGAIN
          postponeExecution.offer(operation);
        else
        // TRY TO RE-EXECUTE THE OPERATION
        if (!getThread(operation.getThreadId()).sendOperation(operation, false))
          // QUEUE FULL: RESCHEDULE IT
          postponeExecution.offer(operation);

      } catch (Throwable t) {
        if (operation != null)
          // FOR ANY ERROR, POSTPONE THE OPERATION AGAIN
          postponeExecution.offer(operation);
      }
    }
    return true;
  }

  /**
   * Flushes data to db and closes the db. Call this once, after vertices and edges creation.
   */
  public void end() {
    // SEND END MESSAGE TO THE WORKER THREADS
    for (OImporterWorkerThread t : threads.values()) {
      try {
        t.sendOperation(new OEndOperation(), true);
      } catch (InterruptedException e) {
        // IGNORE IT
      }
    }

    // WAIT FOR ALL THE WORKER THREADS TO FINISH
    for (OImporterWorkerThread t : threads.values()) {
      try {
        t.join();
      } catch (InterruptedException e) {
        // IGNORE IT
      }
    }

    final long endTime = System.currentTimeMillis();

    if (progressTask != null)
      progressTask.cancel();

    if (verboseLevel > 0) {
      System.out.println("GRAPH IMPORTER TERMINATED on " + new Date() + ". Total processing time: "
          + OIOUtils.getTimeAsString(endTime - startTime));
      dumpStatus(false);
    }

    baseGraph.shutdown();
    factory.close();
  }

  /**
   * Creates a vertex.
   *
   * @param className
   *          Vertex's class name. The class must be declared first by calling {@link #registerVertexClass(String, String, OType)}
   *          method
   * @param id
   *          Vertex's id
   * @param properties
   *          Vertex's properties
   * @throws InterruptedException
   */
  public void createVertex(final String className, final Object id, final Map<String, Object> properties)
      throws InterruptedException {

    if (!vertexClassesUsed.containsKey(className))
      throw new IllegalStateException("Cannot find a vertex of class '" + className
          + "' because it has not been declared at first, by calling registerVertexClass() method");

    // MERGE THE EXISTENT VERTEX ON ITS QUEUE/THREAD
    getThread(baseGraph, className, id).sendOperation(new OCreateVertexOperation(className, id, properties), true);

    checkForBackPressure();
  }

  /**
   *
   * @param sourceVertexClassName
   *          Source vertex's class name. The class must be declared first by calling
   *          {@link #registerVertexClass(String, String, OType)} method
   * @param sourceVertexId
   *          Source vertex's id
   * @param destinationVertexClassName
   *          Destination vertex's class name. The class must be declared first by calling
   *          {@link #registerVertexClass(String, String, OType)} method
   * @param destinationVertexId
   *          Destination vertex's id
   * @param properties
   *          Properties to set in the new edge, null to don't set properties
   * @throws InterruptedException
   */
  public void createEdge(final String edgeClassName, final String sourceVertexClassName, final Object sourceVertexId,
      final String destinationVertexClassName, final Object destinationVertexId, final Map<String, Object> properties)
      throws InterruptedException {
    if (!vertexClassesUsed.containsKey(sourceVertexClassName))
      throw new IllegalStateException("Cannot find a vertex of class '" + sourceVertexClassName
          + "' because it has not been declared at first, by calling registerVertexClass() method");

    if (!vertexClassesUsed.containsKey(destinationVertexClassName))
      throw new IllegalStateException("Cannot find a vertex of class '" + destinationVertexClassName
          + "' because it has not been declared at first, by calling registerVertexClass() method");

    getThread(baseGraph, sourceVertexClassName, sourceVertexId, destinationVertexClassName, destinationVertexId, edgeClassName)
        .sendOperation(new OCreateEdgeOperation(edgeClassName, sourceVertexClassName, sourceVertexId, destinationVertexClassName,
            destinationVertexId, properties), true);

    checkForBackPressure();
  }

  /**
   * Registers a vertex class to be used in the importing process. Before the importing starts, the class, property and indexes are
   * created if not already existent.
   * 
   * @param vertexClassName
   *          Vertex's lass name
   * @param idPropertyName
   *          Vertex's id property name
   * @param idPropertyType
   *          Vertex's id property type
   */
  public void registerVertexClass(final String vertexClassName, final String idPropertyName, final OType idPropertyType) {
    if (factory != null)
      throw new IllegalStateException("Cannot register vertex classes after the import begun");
    vertexClassesUsed.put(vertexClassName, new OPair<String, OType>(idPropertyName, idPropertyType));
    pendingVertexCreation.put(vertexClassName, new ConcurrentHashMap<Object, String>());
  }

  public OPair<String, OType> getRegisteredVertexClass(final String name) {
    return vertexClassesUsed.get(name);
  }

  /**
   * Registers an edge class to be used in the importing process. Before the importing starts, the class is created if not already
   * existent.
   *
   * @param edgeClassName
   *          Vertex's lass name
   */
  public void registerEdgeClass(final String edgeClassName, final String sourceVertexClassName,
      final String destinationVertexClassName) {
    if (factory != null)
      throw new IllegalStateException("Cannot register edge classes after the import begun");
    edgeClassesUsed.add(new OTriple<String, String, String>(edgeClassName, sourceVertexClassName, destinationVertexClassName));
  }

  public int getTotalEstimatedVertices() {
    return totalEstimatedVertices;
  }

  public void setTotalEstimatedVertices(final int totalEstimatedVertices) {
    this.totalEstimatedVertices = totalEstimatedVertices;
  }

  public int getTotalEstimatedEdges() {
    return totalEstimatedEdges;
  }

  public void setTotalEstimatedEdges(final int totalEstimatedEdges) {
    this.totalEstimatedEdges = totalEstimatedEdges;
  }

  public boolean isRegisteredEdgeClass(final String name) {
    return vertexClassesUsed.containsKey(name);
  }

  public OrientGraphFactory getFactory() {
    return factory;
  }

  public OIndex<?> getVertexIndex(final OrientBaseGraph graph, final String name) {
    return graph.getRawGraph().getMetadata().getIndexManager().getIndex(vertexIndexNames.get(name));
  }

  /**
   *
   * @return number of parallel threads used for batch import
   */
  public int getParallel() {
    return parallel;
  }

  /**
   * sets the number of parallel threads to be used for batch insert
   *
   * @param parallel
   *          number of threads (default 4)
   */
  public void setParallel(final int parallel) {
    this.parallel = parallel;
  }

  public int getVerboseLevel() {
    return verboseLevel;
  }

  public void setVerboseLevel(final int verboseLevel) {
    this.verboseLevel = verboseLevel;
  }

  public void setMaxAttemptsToFlushTransaction(final int maxAttemptsToFlushTransaction) {
    this.maxAttemptsToFlushTransaction = maxAttemptsToFlushTransaction;
  }

  public int getMaxAttemptsToFlushTransaction() {
    return maxAttemptsToFlushTransaction;
  }

  public int getQueueSize() {
    return queueSize;
  }

  public void setQueueSize(final int queueSize) {
    this.queueSize = queueSize;
  }

  public int getMaxRetry() {
    return maxRetry;
  }

  public void setMaxRetry(final int maxRetry) {
    this.maxRetry = maxRetry;
  }

  public boolean isTransactional() {
    return transactional;
  }

  public void setTransactional(final boolean transactional) {
    this.transactional = transactional;
  }

  public long getDumpStatsEvery() {
    return dumpStatsEvery;
  }

  public void setDumpStatsEvery(final long dumpStatsEvery) {
    this.dumpStatsEvery = dumpStatsEvery;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(final int batchSize) {
    this.batchSize = batchSize;
  }

  public Boolean getUseLightWeightEdges() {
    return useLightWeightEdges;
  }

  public void setLightweightEdges(final boolean lightweightEdges) {
    this.lightweightEdges = lightweightEdges;
  }

  public int getBackPressureThreshold() {
    return backPressureThreshold;
  }

  public void setBackPressureThreshold(final int backPressureThreshold) {
    this.backPressureThreshold = backPressureThreshold;
  }

  public void addTotalEdges(final long tot) {
    totalEdges.addAndGet(tot);
  }

  public void lockClustersForCommit(final String source, final String destination, final String edge) {
    final List<String> list = new ArrayList<String>(3);
    list.add(source);

    if (destination != null)
      list.add(destination);

    if (edge != null && !lightweightEdges)
      list.add(edge);

    if (list.size() > 1)
      Collections.sort(list);

    // LOCK ORDERED CLUSTERS
    for (String c : list) {
      locks.get(c).lock();
    }
  }

  public void unlockClustersForCommit(final String source, final String destination, final String edge) {
    locks.get(source).unlock();

    if (destination != null)
      locks.get(destination).unlock();

    if (edge != null && !lightweightEdges)
      locks.get(edge).unlock();
  }

  public boolean isVertexLocked(final String className, final Object id) {
    return pendingVertexCreation.get(className).containsKey(id);
  }

  public boolean lockVertexCreationByKey(final OImporterWorkerThread worker, final OrientBaseGraph graph, final String vClassName,
      final Object key, final boolean forceCommit) {
    final ConcurrentHashMap<Object, String> lockManager = pendingVertexCreation.get(vClassName);

    final String workerId = worker.getThreadId();

    String currentLock = null;
    while (true) {
      currentLock = lockManager.putIfAbsent(key, workerId);
      if (currentLock == null || currentLock.equals(workerId)) {
        if (verboseLevel > 1)
          System.out.printf("\n%s locked %s.%s (currentLock=%s)", workerId, vClassName, key, currentLock);
        return true;
      }

      if (verboseLevel > 1)
        System.out.printf("\n%s key %s.%s is already locked, force commit (currentLock=%s)", workerId, vClassName, key,
            currentLock);

      if (forceCommit) {
        forcedCommits.incrementAndGet();

        // ASK TO THE WORKER THREAD THAT IS LOCKING THE KEY TO COMMIT IN ORDER TO RELEASE IT
        final OCommitOperation syncCommitOperation = new OCommitOperation();
        getThread(currentLock).sendPriorityOperation(syncCommitOperation);

        // COMMIT LOCAL TX
        worker.commit(graph);

      } else {
        conflicts.incrementAndGet();
        break;
      }
    }

    if (verboseLevel > 1)
      System.out.printf("\n%s cannot lock %s.%s (currentLock=%s)", workerId, vClassName, key, currentLock);

    return false;
  }

  public void unlockVertexCreationByKey(final String workerId, final String vClassName, final Object key) {
    if (verboseLevel > 1)
      System.out.printf("\n%s unlocking %s.%s...", workerId, vClassName, key);

    final ConcurrentHashMap<Object, String> lockManager = pendingVertexCreation.get(vClassName);
    lockManager.remove(key);
  }

  public void unlockCreationCurrentThread(final String workerId) {
    for (Map.Entry<String, ConcurrentHashMap<Object, String>> vClass : pendingVertexCreation.entrySet()) {
      final ConcurrentHashMap<Object, String> lockMgr = pendingVertexCreation.get(vClass.getKey());
      for (Iterator<Map.Entry<Object, String>> it = lockMgr.entrySet().iterator(); it.hasNext();) {
        final Map.Entry<Object, String> o = it.next();
        if (o.getValue().equals(workerId)) {
          it.remove();
          if (verboseLevel > 1)
            System.out.printf("\n- %s unlocking %s.%s...", workerId, vClass.getKey(), o);
        }
      }
    }
  }

  /**
   * Postpones the execution of an operation (because resources are locked)
   */
  public void postPoneExecution(final OOperation operation) {
    postponeExecution.offer(operation);
  }

  private void createBaseSchema(final OrientGraphNoTx db) {
    final OSchema schema = db.getRawGraph().getMetadata().getSchema();

    // ASSURE BASE V & E CLASSES ARE CREATED
    OClass v = schema.getClass(OrientVertexType.CLASS_NAME);
    if (v == null)
      v = schema.createClass(OrientVertexType.CLASS_NAME);

    OClass e = schema.getClass(OrientEdgeType.CLASS_NAME);
    if (e == null)
      e = schema.createClass(OrientEdgeType.CLASS_NAME);

    // CREATE ALL THE DECLARED VERTEX CLASSES
    for (Map.Entry<String, OPair<String, OType>> entry : vertexClassesUsed.entrySet()) {
      final String className = entry.getKey();
      final String propertyName = entry.getValue().getKey();
      final OType propertyValue = entry.getValue().getValue();

      OClass cls = schema.getClass(className);
      if (cls == null)
        cls = schema.createClass(className, v);

      cls.setOverSize(1.2f);

      OProperty prop = cls.getProperty(propertyName);
      if (prop == null) {
        prop = cls.createProperty(propertyName, propertyValue);
      }

      OIndex<?> index = null;
      if (!prop.getAllIndexes().isEmpty()) {
        // LOOK FOR THE INDEX
        for (OIndex<?> idx : prop.getAllIndexes()) {
          if (idx.getAlgorithm().equalsIgnoreCase("AUTOSHARDING")) {
            // FOUND
            index = idx;
            break;
          }
        }
      }

      if (index == null) {
        // CREATE THE INDEX
        index = cls.createIndex(className + "." + propertyName, OClass.INDEX_TYPE.UNIQUE.toString(), (OProgressListener) null,
            (ODocument) null, "AUTOSHARDING", new String[] { propertyName });
        // ODocument metadata = new ODocument().field("partitions", parallel);
        // index = db.getRawGraph().getMetadata().getIndexManager().createIndex(className + "." + propertyName, "UNIQUE",
        // new OSimpleKeyIndexDefinition(-1, OType.STRING), new int[0], null, metadata, "AUTOSHARDING");
      }

      // REGISTER THE INDEX
      vertexIndexNames.put(className, index.getName());

      // ASSURE THERE ARE ENOUGH NUMBER OF CLUSTERS, OTHERWISE CREATE THE MISSING ONES
      int[] existingClusters = cls.getClusterIds();
      for (int c = existingClusters.length; c < parallel; c++) {
        cls.addCluster(cls.getName() + "_" + c);
      }
    }

    // CREATE ALL THE DECLARED EDGE CLASSES
    for (OTriple<String, String, String> edgeClass : edgeClassesUsed) {
      OClass cls = schema.getClass(edgeClass.getKey());

      if (cls == null)
        cls = schema.createClass(edgeClass.getKey(), e);

      // ASSURE THERE ARE ENOUGH NUMBER OF CLUSTERS, OTHERWISE CREATE THE MISSING ONES
      int[] existingClusters = cls.getClusterIds();
      for (int c = existingClusters.length; c < parallel; c++) {
        cls.addCluster(cls.getName() + "_" + c);
      }
    }
  }

  private OImporterWorkerThread getThread(final OrientBaseGraph graph, final String sourceClassName, final Object sourceKey,
      final String destinationClassName, final Object destinationKey, final String edgeClassName) {
    final OMurmurHash3HashFunction sourceHash = new OMurmurHash3HashFunction();
    final OType sourceKeyType = getVertexIndex(graph, sourceClassName).getKeyTypes()[0];
    sourceHash.setValueSerializer(new OSimpleKeySerializer(sourceKeyType));

    long sourceValue = sourceHash.hashCode(sourceKey);
    sourceValue = sourceValue == Long.MIN_VALUE ? 0 : abs(sourceValue);
    sourceValue = sourceValue % parallel;

    final OType destinationKeyType = getVertexIndex(graph, destinationClassName).getKeyTypes()[0];

    final OMurmurHash3HashFunction destinationHash;
    if (destinationKeyType.equals(sourceKeyType))
      destinationHash = sourceHash;
    else {
      destinationHash = new OMurmurHash3HashFunction();
      destinationHash.setValueSerializer(new OSimpleKeySerializer(destinationKeyType));
    }

    long destinationValue = destinationHash.hashCode(destinationKey);
    destinationValue = destinationValue == Long.MIN_VALUE ? 0 : abs(destinationValue);
    destinationValue = destinationValue % parallel;

    return threads
        .get(sourceClassName + "_" + sourceValue + "-[" + edgeClassName + "]-" + destinationClassName + "_" + destinationValue);
  }

  private OImporterWorkerThread getThread(final String threadName) {
    return threads.get(threadName);
  }

  private OImporterWorkerThread getThread(final OrientBaseGraph graph, final String className, final Object key) {
    final OMurmurHash3HashFunction hash = new OMurmurHash3HashFunction();
    final OType keyType = getVertexIndex(graph, className).getKeyTypes()[0];
    hash.setValueSerializer(new OSimpleKeySerializer(keyType));

    long sourceValue = hash.hashCode(key);
    sourceValue = sourceValue == Long.MIN_VALUE ? 0 : abs(sourceValue);
    sourceValue = sourceValue % parallel;

    return threads.get(className + "_" + sourceValue + "->" + className + "_" + sourceValue);
  }

  private void dumpStatus(final boolean printEstimated) {
    baseGraph.makeActive();

    long totalVertices = 0l;
    final StringBuilder totalVerticesReport = new StringBuilder();
    for (String cls : vertexClassesUsed.keySet()) {
      if (totalVerticesReport.length() > 0)
        totalVerticesReport.append(",");
      final long count = baseGraph.countVertices(cls);
      totalVertices += count;
      totalVerticesReport.append(String.format("%s:%d", cls, count));
    }

    long totalEdges = 0l;
    final StringBuilder totalEdgesReport = new StringBuilder();
    if (lightweightEdges) {
      totalEdges = this.totalEdges.get();
      totalEdgesReport.append('?');
    } else {
      for (OTriple<String, String, String> entry : edgeClassesUsed) {
        if (totalEdgesReport.length() > 0)
          totalEdgesReport.append(",");

        final long count = baseGraph.countEdges(entry.getKey());
        totalEdges += count;
        totalEdgesReport.append(String.format("%s:%d", entry.getKey(), count));
      }
    }

    if (totalEdges == 0 || totalVertices == 0)
      return;

    int recordsLocked = 0;
    for (ConcurrentHashMap<Object, String> o : pendingVertexCreation.values()) {
      recordsLocked += o.size();
    }

    String extra = "";
    if (printEstimated) {
      if (totalEstimatedEdges > 0) {
        final long est = (System.currentTimeMillis() - startTime) * (totalEstimatedEdges - totalEdges) / totalEdges;
        extra = " estRemainingTime=" + OIOUtils.getTimeAsString(est, "s");
      }

      if (extra == null && totalEstimatedVertices > 0) {
        final long est = (System.currentTimeMillis() - startTime) * (totalEstimatedVertices - totalVertices) / totalVertices;
        extra = " estRemainingTime=" + OIOUtils.getTimeAsString(est, "s");
      }
    }

    final long currentConflicts = conflicts.get();
    final long currentForcedCommits = forcedCommits.get();
    final long currentRetries = getRetries();
    final long currentOperationsInQueue = getOperationsInQueue();
    final long currentPostponedOperations = postponeExecution.size();

    System.out
        .print(String.format("VERTICES +%d EDGES +%d confl=%d forcedCommits=%d retries=%d locks=%d opsInQueues=%d postponed=%d\n",
            totalVertices - lastTotalVertices, totalEdges - lastTotalEdges, currentConflicts - lastConflicts,
            (currentForcedCommits - lastForcedCommits), currentRetries - lastRetries, recordsLocked - lastRecordsLocked,
            currentOperationsInQueue - lastOpsInQueues, currentPostponedOperations - lastPostponed));

    System.out.print(String.format(
        "VERTICES [%s]=%d (%d/sec) EDGES [%s]=%d (%d/sec) confl=%d forcedCommits=%d retries=%d locks=%d opsInQueues=%d postponed=%d%s\n\n",
        totalVerticesReport, totalVertices, (totalVertices - lastTotalVertices) / (dumpStatsEvery / 1000), totalEdgesReport,
        totalEdges, (totalEdges - lastTotalEdges) / (dumpStatsEvery / 1000), currentConflicts, forcedCommits.get(), getRetries(),
        recordsLocked, getOperationsInQueue(), postponeExecution.size(), extra));

    lastTotalVertices = totalVertices;
    lastTotalEdges = totalEdges;
    lastConflicts = currentConflicts;
    lastForcedCommits = currentForcedCommits;
    lastRetries = currentRetries;
    lastRecordsLocked = recordsLocked;
    lastOpsInQueues = currentOperationsInQueue;
    lastPostponed = currentPostponedOperations;
  }

  private long getOperationsInQueue() {
    long total = 0l;
    for (OImporterWorkerThread thread : threads.values()) {
      total += thread.getOperationsInQueue();
    }
    return total;
  }

  private long getRetries() {
    long total = 0l;
    for (OImporterWorkerThread thread : threads.values()) {
      total += thread.getRetries();
    }
    return total;
  }

  private void checkForBackPressure() throws InterruptedException {
    while (postponeExecution.size() > backPressureThreshold) {
      executePostponedOperations(backPressureThreshold);
    }
  }
}
