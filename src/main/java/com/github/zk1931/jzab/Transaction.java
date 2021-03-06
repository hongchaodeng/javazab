/**
 * Licensed to the zk1931 under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.  You may obtain a copy of the
 * License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.zk1931.jzab;

import java.nio.ByteBuffer;
import static com.github.zk1931.jzab.proto.ZabMessage.Proposal.ProposalType;

/**
 * Transaction.
 */
public class Transaction {
  private final Zxid zxid;
  private final ByteBuffer body;
  private final int type;

  public Transaction(Zxid zxid, ByteBuffer body) {
    this(zxid, ProposalType.USER_REQUEST_VALUE, body);
  }

  public Transaction(Zxid zxid, int type, ByteBuffer body) {
    this.zxid = zxid;
    this.type = type;
    this.body = body;
  }

  /**
   * Get the id of this transaction.
   *
   * @return id of the transaction
   */
  public Zxid getZxid() {
    return this.zxid;
  }

  /**
   * Get the body of the transaction.
   *
   * @return an array of bytes representing the body of the transaction
   */
  public ByteBuffer getBody() {
    return this.body;
  }

  @Override
  public String toString() {
    return this.zxid + " : " + this.body;
  }

  public int getType() {
    return this.type;
  }
}
