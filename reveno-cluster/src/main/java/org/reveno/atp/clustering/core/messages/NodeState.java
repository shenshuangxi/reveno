/**
 *  Copyright (c) 2015 The original author or authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0

 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */


package org.reveno.atp.clustering.core.messages;

import org.reveno.atp.clustering.api.message.Message;
import org.reveno.atp.core.api.channel.Buffer;

public class NodeState extends Message {

    public static final int TYPE = 0xC5;

    @Override
    public void write(Buffer buffer) {
        buffer.writeLong(viewId);
        buffer.writeLong(transactionId);
        buffer.writeByte(syncMode);
        buffer.writeInt(syncPort);
    }

    @Override
    public void read(Buffer buffer) {
        this.viewId = buffer.readLong();
        this.transactionId = buffer.readLong();
        this.syncMode = buffer.readByte();
        this.syncPort = buffer.readInt();
    }

    @Override
    public int type() {
        return TYPE;
    }

    public long viewId;
    public long transactionId;
    public byte syncMode;
    public int syncPort;

    @Override
    public String toString() {
        return "NodeState{" +
                "viewId=" + viewId +
                ", transactionId=" + transactionId +
                ", syncMode=" + syncMode +
                ", syncPort=" + syncPort +
                '}';
    }

    public NodeState(long viewId, long transactionId, byte syncMode, int syncPort) {
        this.viewId = viewId;
        this.transactionId = transactionId;
        this.syncMode = syncMode;
        this.syncPort = syncPort;
    }

    public NodeState() {
    }
}
