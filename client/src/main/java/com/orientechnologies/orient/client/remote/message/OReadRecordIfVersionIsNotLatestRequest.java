/*
 *
 *  *  Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
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
 *  * For more information: http://orientdb.com
 *
 */
package com.orientechnologies.orient.client.remote.message;

import java.io.IOException;

import com.orientechnologies.orient.client.binary.OChannelBinaryAsynchClient;
import com.orientechnologies.orient.client.remote.OBinaryRequest;
import com.orientechnologies.orient.client.remote.OStorageRemoteSession;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelBinaryProtocol;

public class OReadRecordIfVersionIsNotLatestRequest implements OBinaryRequest {
  private final ORecordId rid;
  private final int       recordVersion;
  private final String    fetchPlan;
  private final boolean   ignoreCache;

  public OReadRecordIfVersionIsNotLatestRequest(ORecordId rid, int recordVersion, String fetchPlan, boolean ignoreCache) {
    this.rid = rid;
    this.recordVersion = recordVersion;
    this.fetchPlan = fetchPlan;
    this.ignoreCache = ignoreCache;
  }

  @Override
  public void write(OChannelBinaryAsynchClient network, OStorageRemoteSession session, int mode) throws IOException {
    network.writeRID(rid);
    network.writeVersion(recordVersion);
    network.writeString(fetchPlan != null ? fetchPlan : "");
    network.writeByte((byte) (ignoreCache ? 1 : 0));
  }

  @Override
  public byte getCommand() {
    return OChannelBinaryProtocol.REQUEST_RECORD_LOAD_IF_VERSION_NOT_LATEST;
  }
}