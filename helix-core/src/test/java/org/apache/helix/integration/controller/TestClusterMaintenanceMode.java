package org.apache.helix.integration.controller;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.google.common.collect.ImmutableMap;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.PropertyKey;
import org.apache.helix.TestHelper;
import org.apache.helix.controller.rebalancer.strategy.CrushEdRebalanceStrategy;
import org.apache.helix.integration.manager.MockParticipantManager;
import org.apache.helix.integration.task.TaskTestBase;
import org.apache.helix.integration.task.WorkflowGenerator;
import org.apache.helix.model.ClusterConfig;
import org.apache.helix.model.ControllerHistory;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.MaintenanceSignal;
import org.apache.helix.monitoring.mbeans.MonitorDomainNames;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.apache.helix.monitoring.mbeans.ClusterStatusMonitor.CLUSTER_DN_KEY;


public class TestClusterMaintenanceMode extends TaskTestBase {
  private static final long TIMEOUT = 180 * 1000L;
  private MockParticipantManager _newInstance;
  private String newResourceAddedDuringMaintenanceMode =
      String.format("%s_%s", WorkflowGenerator.DEFAULT_TGT_DB, 1);
  private HelixDataAccessor _dataAccessor;
  private PropertyKey.Builder _keyBuilder;

  @BeforeClass
  public void beforeClass() throws Exception {
    _numDbs = 1;
    _numNodes = 3;
    _numReplicas = 3;
    _numPartitions = 5;
    super.beforeClass();
    _dataAccessor = _manager.getHelixDataAccessor();
    _keyBuilder = _dataAccessor.keyBuilder();
  }

  @AfterClass
  public void afterClass() throws Exception {
    if (_newInstance != null && _newInstance.isConnected()) {
      _newInstance.syncStop();
    }
    super.afterClass();
  }

  @Test
  public void testNotInMaintenanceMode() {
    boolean isInMaintenanceMode =
        _gSetupTool.getClusterManagementTool().isInMaintenanceMode(CLUSTER_NAME);
    Assert.assertFalse(isInMaintenanceMode);
  }

  @Test(dependsOnMethods = "testNotInMaintenanceMode")
  public void testInMaintenanceMode() {
    _gSetupTool.getClusterManagementTool().enableMaintenanceMode(CLUSTER_NAME, true, TestHelper.getTestMethodName());
    boolean isInMaintenanceMode = _gSetupTool.getClusterManagementTool().isInMaintenanceMode(CLUSTER_NAME);
    Assert.assertTrue(isInMaintenanceMode);
  }

  @Test(dependsOnMethods = "testInMaintenanceMode")
  public void testMaintenanceModeAddNewInstance() {
    _gSetupTool.getClusterManagementTool().enableMaintenanceMode(CLUSTER_NAME, true, TestHelper.getTestMethodName());
    ExternalView prevExternalView = _gSetupTool.getClusterManagementTool()
        .getResourceExternalView(CLUSTER_NAME, WorkflowGenerator.DEFAULT_TGT_DB);
    String instanceName = PARTICIPANT_PREFIX + "_" + (_startPort + 10);
    _gSetupTool.addInstanceToCluster(CLUSTER_NAME, instanceName);
    _newInstance = new MockParticipantManager(ZK_ADDR, CLUSTER_NAME, instanceName);
    _newInstance.syncStart();
    _gSetupTool.getClusterManagementTool().rebalance(CLUSTER_NAME, WorkflowGenerator.DEFAULT_TGT_DB,
        3);
    Assert.assertTrue(_clusterVerifier.verifyByPolling());
    ExternalView newExternalView = _gSetupTool.getClusterManagementTool()
        .getResourceExternalView(CLUSTER_NAME, WorkflowGenerator.DEFAULT_TGT_DB);
    Assert.assertEquals(prevExternalView.getRecord().getMapFields(),
        newExternalView.getRecord().getMapFields());
  }

  @Test(dependsOnMethods = "testMaintenanceModeAddNewInstance")
  public void testMaintenanceModeAddNewResource() {
    _gSetupTool.getClusterManagementTool().addResource(CLUSTER_NAME,
        newResourceAddedDuringMaintenanceMode, 7, "MasterSlave",
        IdealState.RebalanceMode.FULL_AUTO.name(), CrushEdRebalanceStrategy.class.getName());
    _gSetupTool.getClusterManagementTool().rebalance(CLUSTER_NAME,
        newResourceAddedDuringMaintenanceMode, 3);
    Assert.assertTrue(_clusterVerifier.verifyByPolling());
    ExternalView externalView = _gSetupTool.getClusterManagementTool()
        .getResourceExternalView(CLUSTER_NAME, newResourceAddedDuringMaintenanceMode);
    Assert.assertNull(externalView);
  }

  @Test(dependsOnMethods = "testMaintenanceModeAddNewResource")
  public void testMaintenanceModeInstanceDown() {
    _participants[0].syncStop();
    Assert.assertTrue(_clusterVerifier.verifyByPolling());
    ExternalView externalView = _gSetupTool.getClusterManagementTool()
        .getResourceExternalView(CLUSTER_NAME, WorkflowGenerator.DEFAULT_TGT_DB);
    for (Map<String, String> stateMap : externalView.getRecord().getMapFields().values()) {
      Assert.assertTrue(stateMap.values().contains("MASTER"));
    }
  }

  @Test(dependsOnMethods = "testMaintenanceModeInstanceDown")
  public void testMaintenanceModeInstanceBack() {
    _participants[0] =
        new MockParticipantManager(ZK_ADDR, CLUSTER_NAME, _participants[0].getInstanceName());
    _participants[0].syncStart();
    Assert.assertTrue(_clusterVerifier.verifyByPolling());
    ExternalView externalView = _gSetupTool.getClusterManagementTool()
        .getResourceExternalView(CLUSTER_NAME, WorkflowGenerator.DEFAULT_TGT_DB);
    for (Map<String, String> stateMap : externalView.getRecord().getMapFields().values()) {
      if (stateMap.containsKey(_participants[0].getInstanceName())) {
        Assert.assertEquals(stateMap.get(_participants[0].getInstanceName()), "SLAVE");
      }
    }
  }

  @Test(dependsOnMethods = "testMaintenanceModeInstanceBack")
  public void testExitMaintenanceModeNewResourceRecovery() {
    _gSetupTool.getClusterManagementTool().enableMaintenanceMode(CLUSTER_NAME, false);
    Assert.assertTrue(_clusterVerifier.verifyByPolling());
    ExternalView externalView = _gSetupTool.getClusterManagementTool()
        .getResourceExternalView(CLUSTER_NAME, newResourceAddedDuringMaintenanceMode);
    Assert.assertEquals(externalView.getRecord().getMapFields().size(), 7);
    for (Map<String, String> stateMap : externalView.getRecord().getMapFields().values()) {
      Assert.assertTrue(stateMap.values().contains("MASTER"));
    }
  }

  /**
   * Test that the auto-exit functionality works.
   */
  @Test(dependsOnMethods = "testExitMaintenanceModeNewResourceRecovery")
  public void testAutoExitMaintenanceMode() throws Exception {
    // Set the config for auto-exiting maintenance mode
    ClusterConfig clusterConfig = _manager.getConfigAccessor().getClusterConfig(CLUSTER_NAME);
    clusterConfig.setMaxOfflineInstancesAllowed(2);
    clusterConfig.setNumOfflineInstancesForAutoExit(1);
    _manager.getConfigAccessor().setClusterConfig(CLUSTER_NAME, clusterConfig);

    // Kill 3 instances
    for (int i = 0; i < 3; i++) {
      _participants[i].syncStop();
    }
    TestHelper.verify(() -> _dataAccessor.getChildNames(_keyBuilder.liveInstances()).size() == 0, 2000L);

    // Check that the cluster is in maintenance
    MaintenanceSignal maintenanceSignal = _dataAccessor.getProperty(_keyBuilder.maintenance());
    Assert.assertNotNull(maintenanceSignal);

    // Now bring up 2 instances
    for (int i = 0; i < 2; i++) {
      String instanceName = PARTICIPANT_PREFIX + "_" + (_startPort + i);
      _participants[i] = new MockParticipantManager(ZK_ADDR, CLUSTER_NAME, instanceName);
      _participants[i].syncStart();
    }
    TestHelper.verify(() -> _dataAccessor.getChildNames(_keyBuilder.liveInstances()).size() == 3, 2000L);

    // Check that the cluster is no longer in maintenance (auto-recovered)
    maintenanceSignal = _dataAccessor.getProperty(_keyBuilder.maintenance());
    Assert.assertNull(maintenanceSignal);
  }

  @Test(dependsOnMethods = "testAutoExitMaintenanceMode")
  public void testNoAutoExitWhenManuallyPutInMaintenance() throws Exception {
    // Manually put the cluster in maintenance
    _gSetupTool.getClusterManagementTool().manuallyEnableMaintenanceMode(CLUSTER_NAME, true, null,
        null);

    // Kill 2 instances, which makes it a total of 3 down instances
    for (int i = 0; i < 2; i++) {
      _participants[i].syncStop();
    }
    TestHelper.verify(() -> _dataAccessor.getChildNames(_keyBuilder.liveInstances()).size() == 0, 2000L);

    // Now bring up all instances
    for (int i = 0; i < 3; i++) {
      String instanceName = PARTICIPANT_PREFIX + "_" + (_startPort + i);
      _participants[i] = new MockParticipantManager(ZK_ADDR, CLUSTER_NAME, instanceName);
      _participants[i].syncStart();
    }
    TestHelper.verify(() -> _dataAccessor.getChildNames(_keyBuilder.liveInstances()).size() == 3, 2000L);

    // The cluster should still be in maintenance because it was enabled manually
    MaintenanceSignal maintenanceSignal = _dataAccessor.getProperty(_keyBuilder.maintenance());
    Assert.assertNotNull(maintenanceSignal);
  }

  /**
   * Test that manual triggering of maintenance mode overrides auto-enabled maintenance.
   * @throws InterruptedException
   */
  @Test(dependsOnMethods = "testNoAutoExitWhenManuallyPutInMaintenance")
  public void testManualEnablingOverridesAutoEnabling() throws Exception {
    // Exit maintenance mode manually
    _gSetupTool.getClusterManagementTool().manuallyEnableMaintenanceMode(CLUSTER_NAME, false, null,
        null);

    // Kill 3 instances, which would put cluster in maintenance automatically
    for (int i = 0; i < 3; i++) {
      _participants[i].syncStop();
    }
    TestHelper.verify(() -> _dataAccessor.getChildNames(_keyBuilder.liveInstances()).size() == 0, 2000L);

    // Check that maintenance signal was triggered by Controller
    MaintenanceSignal maintenanceSignal = _dataAccessor.getProperty(_keyBuilder.maintenance());
    Assert.assertNotNull(maintenanceSignal);
    Assert.assertEquals(maintenanceSignal.getTriggeringEntity(),
        MaintenanceSignal.TriggeringEntity.CONTROLLER);

    // Manually enable maintenance mode with customFields
    Map<String, String> customFields = ImmutableMap.of("LDAP", "hulee", "JIRA", "HELIX-999",
        "TRIGGERED_BY", "SHOULD NOT BE RECORDED");
    _gSetupTool.getClusterManagementTool().manuallyEnableMaintenanceMode(CLUSTER_NAME, true, null,
        customFields);
    TestHelper.verify(() -> _dataAccessor.getProperty(_keyBuilder.maintenance()) != null, 2000L);

    // Check that maintenance mode has successfully overwritten with the right TRIGGERED_BY field
    maintenanceSignal = _dataAccessor.getProperty(_keyBuilder.maintenance());
    Assert.assertEquals(maintenanceSignal.getTriggeringEntity(),
        MaintenanceSignal.TriggeringEntity.USER);
    for (Map.Entry<String, String> entry : customFields.entrySet()) {
      if (entry.getKey().equals("TRIGGERED_BY")) {
        Assert.assertEquals(maintenanceSignal.getRecord().getSimpleField(entry.getKey()), "USER");
      } else {
        Assert.assertEquals(maintenanceSignal.getRecord().getSimpleField(entry.getKey()),
            entry.getValue());
      }
    }
  }

  /**
   * Test that maxNumPartitionPerInstance still applies (if any Participant has more replicas than
   * the threshold, the cluster should not auto-exit maintenance mode).
   * @throws InterruptedException
   */
  @Test(dependsOnMethods = "testManualEnablingOverridesAutoEnabling")
  public void testMaxPartitionLimit() throws Exception {
    // Manually exit maintenance mode
    _gSetupTool.getClusterManagementTool().manuallyEnableMaintenanceMode(CLUSTER_NAME, false, null,
        null);
    TestHelper.verify(() -> _dataAccessor.getProperty(_keyBuilder.maintenance()) != null, 2000L);

    // Since 3 instances are missing, the cluster should have gone back under maintenance
    // automatically
    MaintenanceSignal maintenanceSignal = _dataAccessor.getProperty(_keyBuilder.maintenance());
    Assert.assertNotNull(maintenanceSignal);
    Assert.assertEquals(maintenanceSignal.getTriggeringEntity(),
        MaintenanceSignal.TriggeringEntity.CONTROLLER);
    Assert.assertEquals(maintenanceSignal.getAutoTriggerReason(),
        MaintenanceSignal.AutoTriggerReason.MAX_INSTANCES_UNABLE_TO_ACCEPT_ONLINE_REPLICAS);

    // Bring up all instances
    for (int i = 0; i < 3; i++) {
      String instanceName = PARTICIPANT_PREFIX + "_" + (_startPort + i);
      _participants[i] = new MockParticipantManager(ZK_ADDR, CLUSTER_NAME, instanceName);
      _participants[i].syncStart();
    }
    TestHelper.verify(() -> _dataAccessor.getChildNames(_keyBuilder.liveInstances()).size() == 3, 2000L);

    // Check that the cluster exited maintenance
    maintenanceSignal = _dataAccessor.getProperty(_keyBuilder.maintenance());
    Assert.assertNull(maintenanceSignal);

    // Kill 3 instances, which would put cluster in maintenance automatically
    for (int i = 0; i < 3; i++) {
      _participants[i].syncStop();
    }
    TestHelper.verify(() -> _dataAccessor.getChildNames(_keyBuilder.liveInstances()).size() == 0, 2000L);

    // Check that cluster is back under maintenance
    maintenanceSignal = _dataAccessor.getProperty(_keyBuilder.maintenance());
    Assert.assertNotNull(maintenanceSignal);
    Assert.assertEquals(maintenanceSignal.getTriggeringEntity(),
        MaintenanceSignal.TriggeringEntity.CONTROLLER);
    Assert.assertEquals(maintenanceSignal.getAutoTriggerReason(),
        MaintenanceSignal.AutoTriggerReason.MAX_INSTANCES_UNABLE_TO_ACCEPT_ONLINE_REPLICAS);

    // Set the cluster config for auto-exiting maintenance mode
    ClusterConfig clusterConfig = _manager.getConfigAccessor().getClusterConfig(CLUSTER_NAME);
    // Setting MaxPartitionsPerInstance to 1 will prevent the cluster from exiting maintenance mode
    // automatically because the instances currently have more than 1
    clusterConfig.setMaxPartitionsPerInstance(1);
    _manager.getConfigAccessor().setClusterConfig(CLUSTER_NAME, clusterConfig);
    TestHelper.verify(
        () -> ((ClusterConfig) _dataAccessor.getProperty(_keyBuilder.clusterConfig())).getMaxPartitionsPerInstance() == 1,
        2000L);


    // Now bring up all instances
    for (int i = 0; i < 3; i++) {
      String instanceName = PARTICIPANT_PREFIX + "_" + (_startPort + i);
      _participants[i] = new MockParticipantManager(ZK_ADDR, CLUSTER_NAME, instanceName);
      _participants[i].syncStart();
    }
    TestHelper.verify(() -> _dataAccessor.getChildNames(_keyBuilder.liveInstances()).size() == 3, 2000L);

    // Check that the cluster is still in maintenance (should not have auto-exited because it would
    // fail the MaxPartitionsPerInstance check)
    maintenanceSignal = _dataAccessor.getProperty(_keyBuilder.maintenance());
    Assert.assertNotNull(maintenanceSignal);
    Assert.assertEquals(maintenanceSignal.getTriggeringEntity(),
        MaintenanceSignal.TriggeringEntity.CONTROLLER);
    Assert.assertEquals(maintenanceSignal.getAutoTriggerReason(),
        MaintenanceSignal.AutoTriggerReason.MAX_PARTITION_PER_INSTANCE_EXCEEDED);

    // Check if failed rebalance counter is updated
    boolean result = TestHelper.verify(() -> {
      try {
        Long value =
            (Long) _server.getAttribute(getMbeanName(CLUSTER_NAME), "RebalanceFailureCounter");
        return value != null && (value > 0);
      } catch (Exception e) {
        return false;
      }
    }, TIMEOUT);
    Assert.assertTrue(result);

    // Check failed continuous task rebalance counter is not updated
    result = TestHelper.verify(() -> {
      try {
        Long value = (Long) _server
            .getAttribute(getMbeanName(CLUSTER_NAME), "ContinuousTaskRebalanceFailureCount");
        return value != null && (value == 0);
      } catch (Exception e) {
        return false;
      }
    }, TIMEOUT);
    Assert.assertTrue(result);

    // Check if failed continuous resource rebalance counter is updated
    result = TestHelper.verify(() -> {
      try {
        Long value = (Long) _server
            .getAttribute(getMbeanName(CLUSTER_NAME), "ContinuousResourceRebalanceFailureCount");
        return value != null && (value > 0);
      } catch (Exception e) {
        return false;
      }
    }, TIMEOUT);
    Assert.assertTrue(result);
  }


  private ObjectName getMbeanName(String clusterName) throws MalformedObjectNameException {
    String clusterBeanName = String.format("%s=%s", CLUSTER_DN_KEY, clusterName);
    return new ObjectName(
        String.format("%s:%s", MonitorDomainNames.ClusterStatus.name(), clusterBeanName));
  }

  /**
   * Test that the Controller correctly records maintenance history in various situations.
   * @throws InterruptedException
   */
  @Test(dependsOnMethods = "testMaxPartitionLimit")
  public void testMaintenanceHistory() throws Exception {
    // In maintenance mode, by controller, for MAX_PARTITION_PER_INSTANCE_EXCEEDED
    ControllerHistory history = _dataAccessor.getProperty(_keyBuilder.controllerLeaderHistory());
    Map<String, String> lastHistoryEntry = convertStringToMap(
        history.getMaintenanceHistoryList().get(history.getMaintenanceHistoryList().size() - 1));

    // **The KV pairs are hard-coded in here for the ease of reading!**
    Assert.assertEquals(lastHistoryEntry.get("OPERATION_TYPE"), "ENTER");
    Assert.assertEquals(lastHistoryEntry.get("TRIGGERED_BY"), "CONTROLLER");
    Assert.assertEquals(lastHistoryEntry.get("AUTO_TRIGGER_REASON"),
        "MAX_PARTITION_PER_INSTANCE_EXCEEDED");

    // Remove the maxPartitionPerInstance config
    ClusterConfig clusterConfig = _manager.getConfigAccessor().getClusterConfig(CLUSTER_NAME);
    clusterConfig.setMaxPartitionsPerInstance(-1);
    _manager.getConfigAccessor().setClusterConfig(CLUSTER_NAME, clusterConfig);

    TestHelper.verify(() -> _dataAccessor.getProperty(_keyBuilder.maintenance()) == null, 2000L);

    // Now check that the cluster exited maintenance
    // EXIT, CONTROLLER, for MAX_PARTITION_PER_INSTANCE_EXCEEDED
    history = _dataAccessor.getProperty(_keyBuilder.controllerLeaderHistory());
    lastHistoryEntry = convertStringToMap(
        history.getMaintenanceHistoryList().get(history.getMaintenanceHistoryList().size() - 1));
    Assert.assertEquals(lastHistoryEntry.get("OPERATION_TYPE"), "EXIT");
    Assert.assertEquals(lastHistoryEntry.get("TRIGGERED_BY"), "CONTROLLER");
    Assert.assertEquals(lastHistoryEntry.get("AUTO_TRIGGER_REASON"),
        "MAX_PARTITION_PER_INSTANCE_EXCEEDED");

    // Manually put the cluster in maintenance with a custom field
    Map<String, String> customFieldMap = ImmutableMap.of("k1", "v1", "k2", "v2");
    _gSetupTool.getClusterManagementTool()
        .manuallyEnableMaintenanceMode(CLUSTER_NAME, true, TestHelper.getTestMethodName(), customFieldMap);
    TestHelper.verify(() -> _dataAccessor.getProperty(_keyBuilder.maintenance()) != null, 2000L);

    // ENTER, USER, for reason TEST, no internalReason
    history = _dataAccessor.getProperty(_keyBuilder.controllerLeaderHistory());
    lastHistoryEntry =
        convertStringToMap(history.getMaintenanceHistoryList().get(history.getMaintenanceHistoryList().size() - 1));
    Assert.assertEquals(lastHistoryEntry.get("OPERATION_TYPE"), "ENTER");
    Assert.assertEquals(lastHistoryEntry.get("TRIGGERED_BY"), "USER");
    Assert.assertEquals(lastHistoryEntry.get("REASON"), TestHelper.getTestMethodName());
    Assert.assertNull(lastHistoryEntry.get("AUTO_TRIGGER_REASON"));
  }

  /**
   * Convert a String representation of a Map into a Map object for verification purposes.
   * @param value
   * @return
   */
  private static Map<String, String> convertStringToMap(String value) throws IOException {
    return new ObjectMapper().readValue(value,
        TypeFactory.defaultInstance().constructMapType(HashMap.class, String.class, String.class));
  }
}
