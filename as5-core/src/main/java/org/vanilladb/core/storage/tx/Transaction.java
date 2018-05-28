/*******************************************************************************
 * Copyright 2017 vanilladb.org
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.vanilladb.core.storage.tx;

import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;
import java.util.HashMap;

import org.vanilladb.core.sql.BigIntConstant;
import org.vanilladb.core.sql.Constant;
import org.vanilladb.core.sql.IntegerConstant;
import org.vanilladb.core.server.VanillaDb;
import org.vanilladb.core.storage.buffer.BufferMgr;
import org.vanilladb.core.storage.tx.concurrency.ConcurrencyMgr;
import org.vanilladb.core.storage.tx.recovery.RecoveryMgr;
import org.vanilladb.core.storage.record.RecordId;
import org.vanilladb.core.storage.record.RecordFile;
/**
 * Provides transaction management for clients, ensuring that all transactions
 * are recoverable, and in general satisfy the ACID properties with specified
 * isolation level.
 */
public class Transaction {
	private static Logger logger = Logger.getLogger(Transaction.class.getName());

	private RecoveryMgr recoveryMgr;
	private ConcurrencyMgr concurMgr;
	private BufferMgr bufferMgr;
	private List<TransactionLifecycleListener> lifecycleListeners;
	private long txNum;
	private boolean readOnly;
	
	public HashMap<String, fld_rid_val> hashmap;
	public HashMap<String, RecordFile> recordFiles;
	
	public class fld_rid_val{
		public String fldname;
		public RecordId rid;
		public Constant constant;
		public fld_rid_val(String fldname, RecordId rid, Constant constant) {
			this.fldname = fldname;
			this.rid = rid;
			this.constant = constant;
		}
	}
	/**
	 * Creates a new transaction and associates it with a recovery manager, a
	 * concurrency manager, and a buffer manager. This constructor depends on
	 * the file, log, and buffer managers from {@link VanillaDb}, which are
	 * created during system initialization. Thus this constructor cannot be
	 * called until {@link VanillaDb#init(String)} is called first.
	 * 
	 * @param txMgr
	 *            the transaction manager
	 * @param concurMgr
	 *            the associated concurrency manager
	 * @param recoveryMgr
	 *            the associated recovery manager
	 * @param bufferMgr
	 *            the associated buffer manager
	 * @param readOnly
	 *            is read-only mode
	 * @param txNum
	 *            the number of the transaction
	 */
	public Transaction(TransactionMgr txMgr, TransactionLifecycleListener concurMgr,
			TransactionLifecycleListener recoveryMgr, TransactionLifecycleListener bufferMgr, boolean readOnly,
			long txNum) {
		this.concurMgr = (ConcurrencyMgr) concurMgr;
		this.recoveryMgr = (RecoveryMgr) recoveryMgr;
		this.bufferMgr = (BufferMgr) bufferMgr;
		this.txNum = txNum;
		this.readOnly = readOnly;
		
		this.hashmap = new HashMap<String, fld_rid_val>();
		this.recordFiles = new HashMap<String, RecordFile>();

		lifecycleListeners = new LinkedList<TransactionLifecycleListener>();
		// XXX: A transaction manager must be added before a recovery manager to
		// prevent the following scenario:
		// <COMMIT 1>
		// <NQCKPT 1,2>
		//
		// Although, it may create another scenario like this:
		// <NQCKPT 2>
		// <COMMIT 1>
		// But the current algorithm can still recovery correctly during this
		// scenario.
		addLifecycleListener(txMgr);
		/*
		 * A recover manager must be added before a concurrency manager. For
		 * example, if the transaction need to roll back, it must hold all locks
		 * until the recovery procedure complete.
		 */
		addLifecycleListener(recoveryMgr);
		addLifecycleListener(concurMgr);
		addLifecycleListener(bufferMgr);
	}
	
	//HashMap<String key, Integer value>();
	public fld_rid_val hashmap_get(String key) {
		return this.hashmap.get(key);
	}
	
	public fld_rid_val hashmap_put(String key,  fld_rid_val c) {
		return this.hashmap.put(key, c);
	}
	
	public RecordFile recordFiles_get(String file_name) {
		return this.recordFiles.get(file_name);
	}
	
	public RecordFile recordFiles_put(String file_name, RecordFile rf) {
		return this.recordFiles.put(file_name, rf);
	}
	
	public void addLifecycleListener(TransactionLifecycleListener listener) {
		lifecycleListeners.add(listener);
	}

	/**
	 * Commits the current transaction. Flushes all modified blocks (and their
	 * log records), writes and flushes a commit record to the log, releases all
	 * locks, and unpins any pinned blocks.
	 */
	public void commit() {
		//in-place write 
			if(!this.isReadOnly() || this.isReadOnly()) {
				//record file correspond to the rid should do the in-place write
				for (Map.Entry<String, fld_rid_val> entry : this.hashmap.entrySet()) {    
					fld_rid_val val = entry.getValue(); 
					RecordFile rf = this.recordFiles_get(val.rid.block().fileName());
					rf.moveToRecordId(val.rid);		
					rf.in_place_setVal(val.fldname, val.constant);
				}  
			}
			
		//original code
		for (TransactionLifecycleListener l : lifecycleListeners)
			l.onTxCommit(this);

		if (logger.isLoggable(Level.FINE))
			logger.fine("transaction " + txNum + " committed");
	}

	/**
	 * Rolls back the current transaction. Undoes any modified values, flushes
	 * those blocks, writes and flushes a rollback record to the log, releases
	 * all locks, and unpins any pinned blocks.
	 */
	public void rollback() {
		for (TransactionLifecycleListener l : lifecycleListeners) {

			l.onTxRollback(this);
		}

		if (logger.isLoggable(Level.FINE))
			logger.fine("transaction " + txNum + " rolled back");
	}

	/**
	 * Finishes the current statement. Releases slocks obtained so far for
	 * repeatable read isolation level and does nothing in serializable
	 * isolation level. This method should be called after each SQL statement.
	 */
	public void endStatement() {
		for (TransactionLifecycleListener l : lifecycleListeners)
			l.onTxEndStatement(this);
	}

	public long getTransactionNumber() {
		return this.txNum;
	}

	public boolean isReadOnly() {
		return this.readOnly;
	}

	public RecoveryMgr recoveryMgr() {
		return recoveryMgr;
	}

	public ConcurrencyMgr concurrencyMgr() {
		return concurMgr;
	}

	public BufferMgr bufferMgr() {
		return bufferMgr;
	}
}
