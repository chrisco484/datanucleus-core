/**********************************************************************
Copyright (c) 2010 Peter Dettman and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
2013 Andy Jefferson - converted to general purpose queue for all operations
    ...
**********************************************************************/
package org.datanucleus.flush;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.datanucleus.ExecutionContext;
import org.datanucleus.state.ObjectProvider;
import org.datanucleus.store.scostore.CollectionStore;
import org.datanucleus.store.scostore.ListStore;
import org.datanucleus.store.scostore.MapStore;
import org.datanucleus.store.scostore.Store;
import org.datanucleus.store.types.SCOUtils;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;
import org.datanucleus.util.StringUtils;

/**
 * Queue of operations to be performed when operating in MANUAL FlushMode.
 * This queue will typically contain operations on second class objects, such as Collections or Maps.
 * They are queued in the order they are invoked by the user. 
 * The queue will contain all operations to be performed for an ExecutionContext. There are two methods for
 * processing them, one processing all (for use in the future when we support that mode in full), and one
 * that processes all for a particular SCO (backing store).
 * TODO Pass operations for PERSIST, DELETE, UPDATE through here too so they can be processed more efficiently?
 */
public class OperationQueue
{
    protected List<Operation> queuedOperations = new ArrayList<Operation>();

    /**
     * Method to add the specified operation to the operation queue.
     * @param oper Operation
     */
    public synchronized void enqueue(Operation oper)
    {
        queuedOperations.add(oper);
    }

    /**
     * Convenience method to log the current operation queue.
     */
    public synchronized void log()
    {
        NucleusLogger.GENERAL.debug(">> OperationQueue :" + (queuedOperations.isEmpty() ? " Empty" : ("" + queuedOperations.size() + " operations")));
        for (Operation op : queuedOperations)
        {
            NucleusLogger.GENERAL.debug(">> " + op);
        }
    }

    public void clear()
    {
        queuedOperations.clear();
    }

    /**
     * Method to provide access to inspect the queued operations. The returned list is unmodifiable.
     * @return The queued operations
     */
    public List<Operation> getOperations()
    {
        return Collections.unmodifiableList(queuedOperations);
    }

    public void removeOperations(List<Operation> removedOps)
    {
        queuedOperations.removeAll(removedOps);
    }

    /**
     * Method to perform all operations queued.
     * Those operations are then removed from the queue.
     */
    public synchronized void performAll()
    {
        for (Operation op : queuedOperations)
        {
            op.perform();
        }
        queuedOperations.clear();
    }

    /**
     * Method to perform all operations queued for the specified ObjectProvider and backing store.
     * Those operations are then removed from the queue.
     * @param store The backing store
     * @param op ObjectProvider
     */
    public synchronized void performAll(Store store, ObjectProvider op)
    {
        if (NucleusLogger.PERSISTENCE.isDebugEnabled())
        {
            NucleusLogger.PERSISTENCE.debug(Localiser.msg("023005", op.getObjectAsPrintable(), store.getOwnerMemberMetaData().getFullFieldName()));
        }

        // Extract those operations for the specified backing store
        List<Operation> flushOperations = new ArrayList<Operation>();
        ListIterator<Operation> operIter = queuedOperations.listIterator();
        while (operIter.hasNext())
        {
            Operation oper = operIter.next();
            if (oper instanceof SCOOperation && ((SCOOperation)oper).getStore() == store)
            {
                flushOperations.add(oper);
                operIter.remove();
            }
        }

        // TODO Cater for Lists where cascade delete is enabled but we want to only allow cascade delete if the element isn't later added at a different place in the list.
        ListIterator<Operation> flushOperIter = flushOperations.listIterator();
        while (flushOperIter.hasNext())
        {
            Operation oper = flushOperIter.next();
            if (store instanceof CollectionStore)
            {
                if (!(store instanceof ListStore))
                {
                    if (isAddFollowedByRemoveOnSameSCO(store, op, oper, flushOperIter))
                    {
                        // add+remove of the same element - ignore this and the next one
                        flushOperIter.next();
                    }
                    else if (isRemoveFollowedByAddOnSameSCO(store, op, oper, flushOperIter))
                    {
                        // remove+add of the same element - ignore this and the next one
                        flushOperIter.next();
                    }
                    else
                    {
                        oper.perform();
                    }
                }
                else
                {
                    oper.perform();
                }
            }
            else if (store instanceof MapStore)
            {
                if (isPutFollowedByRemoveOnSameSCO(store, op, oper, flushOperIter))
                {
                    // put+remove of the same key - ignore this and the next one
                    flushOperIter.next();
                }
                else
                {
                    oper.perform();
                }
            }
            else
            {
                oper.perform();
            }
        }
    }

    public void clearPersistDeleteUpdateOperations()
    {
        if (queuedOperations != null)
        {
            Iterator<Operation> opsIter = queuedOperations.iterator();
            while (opsIter.hasNext())
            {
                Operation op = opsIter.next();
                if (op instanceof PersistOperation || op instanceof DeleteOperation || op instanceof UpdateMemberOperation)
                {
                    opsIter.remove();
                }
            }
        }
    }

    /**
     * Method to process all SCO operations where the SCOs don't use a backing store.
     * This will check for add+remove/remove+add and perform cascade delete as appropriate.
     * It will then remove the SCO operations from the queue. It doesn't actually process the queued operations since when you have
     * no backing store there is nothing to do.
     * @param ec ExecutionContext
     */
    public void processOperationsForNoBackingStoreSCOs(ExecutionContext ec)
    {
        if (queuedOperations != null && !ec.getStoreManager().usesBackedSCOWrappers())
        {
            // Make use of OperationQueue for any cascade deletes that may be needed as a result of removal from collections/maps
            List<Operation> opsToIgnore = new ArrayList();
            List<Object> objectsToCascadeDelete = null;
            Iterator<Operation> opsIter = queuedOperations.iterator();
            while (opsIter.hasNext())
            {
                Operation op = opsIter.next();
                if (op instanceof CollectionRemoveOperation)
                {
                    // TODO Need to catch cases where user has persisted a new owner object (of same type) and this in the same field.
                    CollectionRemoveOperation collRemoveOp = (CollectionRemoveOperation)op;
                    if (collRemoveOp.getStore() == null && SCOUtils.hasDependentElement(collRemoveOp.getMemberMetaData()))
                    {
                        // Check for addition of the same element, hence avoiding cascade-delete
                        boolean needsRemoving = true;
                        if (queuedOperations.size() > 1)
                        {
                            // Check for later addition that negates the cascade delete
                            Iterator<Operation> subOpsIter = queuedOperations.iterator();
                            while (subOpsIter.hasNext())
                            {
                                Operation subOp = subOpsIter.next();
                                if (subOp instanceof CollectionAddOperation)
                                {
                                    CollectionAddOperation collAddOp = (CollectionAddOperation)subOp;
                                    if (collRemoveOp.getValue().equals(collAddOp.getValue()))
                                    {
                                        needsRemoving = false;
                                        break;
                                    }
                                }
                                else if (subOp instanceof PersistOperation)
                                {
                                    // Check whether this is the persist of an object the same type as the owner of the removed element (i.e assigned to new owner)
                                    PersistOperation persOp = (PersistOperation)subOp;
                                    if (persOp.getObjectProvider().getObject().getClass().equals(collRemoveOp.getObjectProvider().getObject().getClass()))
                                    {
                                        Collection persColl = (Collection) persOp.getObjectProvider().provideField(collRemoveOp.getMemberMetaData().getAbsoluteFieldNumber());
                                        if (persColl != null && persColl.contains(collRemoveOp.getValue()))
                                        {
                                            needsRemoving = false;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                        if (needsRemoving)
                        {
                            if (objectsToCascadeDelete == null)
                            {
                                objectsToCascadeDelete = new ArrayList();
                            }
                            NucleusLogger.GENERAL.info(">> Flush collection element needs cascade delete " + collRemoveOp.getValue());
                            objectsToCascadeDelete.add(collRemoveOp.getValue());
                        }
                    }
                }
                else if (op instanceof MapRemoveOperation)
                {
                    MapRemoveOperation mapRemoveOp = (MapRemoveOperation)op;
                    if (mapRemoveOp.getStore() == null)
                    {
                        if (SCOUtils.hasDependentKey(mapRemoveOp.getMemberMetaData()))
                        {
                            // Check for later addition of the same key, hence avoiding cascade-delete
                            boolean keyNeedsRemoving = true;
                            if (queuedOperations.size() > 1)
                            {
                                // Check for later addition that negates the cascade delete
                                Iterator<Operation> subOpsIter = queuedOperations.iterator();
                                while (subOpsIter.hasNext())
                                {
                                    Operation subOp = subOpsIter.next();
                                    if (subOp instanceof MapPutOperation)
                                    {
                                        MapPutOperation mapPutOp = (MapPutOperation)subOp;
                                        if (mapRemoveOp.getKey().equals(mapPutOp.getKey()))
                                        {
                                            keyNeedsRemoving = false;
                                            break;
                                        }
                                    }
                                    else if (subOp instanceof PersistOperation)
                                    {
                                        // Check whether this is the persist of an object the same type as the owner of the removed key (i.e assigned to new owner)
                                        PersistOperation persOp = (PersistOperation)subOp;
                                        if (persOp.getObjectProvider().getObject().getClass().equals(mapRemoveOp.getObjectProvider().getObject().getClass()))
                                        {
                                            Map persMap = (Map) persOp.getObjectProvider().provideField(mapRemoveOp.getMemberMetaData().getAbsoluteFieldNumber());
                                            if (persMap != null && persMap.containsKey(mapRemoveOp.getKey()))
                                            {
                                                keyNeedsRemoving = false;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                            if (keyNeedsRemoving)
                            {
                                if (objectsToCascadeDelete == null)
                                {
                                    objectsToCascadeDelete = new ArrayList();
                                }
                                objectsToCascadeDelete.add(mapRemoveOp.getKey());
                            }
                        }
                        else if (SCOUtils.hasDependentValue(mapRemoveOp.getMemberMetaData()))
                        {
                            // Check for later addition of the same value, hence avoiding cascade-delete
                            boolean valNeedsRemoving = true;
                            if (queuedOperations.size() > 1)
                            {
                                // Check for later addition that negates the cascade delete
                                Iterator<Operation> subOpsIter = queuedOperations.iterator();
                                while (subOpsIter.hasNext())
                                {
                                    Operation subOp = subOpsIter.next();
                                    if (subOp instanceof MapPutOperation)
                                    {
                                        MapPutOperation mapPutOp = (MapPutOperation)subOp;
                                        if (mapRemoveOp.getValue().equals(mapPutOp.getValue()))
                                        {
                                            valNeedsRemoving = false;
                                            break;
                                        }
                                    }
                                    else if (subOp instanceof PersistOperation)
                                    {
                                        // Check whether this is the persist of an object the same type as the owner of the removed value (i.e assigned to new owner)
                                        PersistOperation persOp = (PersistOperation)subOp;
                                        if (persOp.getObjectProvider().getObject().getClass().equals(mapRemoveOp.getObjectProvider().getObject().getClass()))
                                        {
                                            Map persMap = (Map) persOp.getObjectProvider().provideField(mapRemoveOp.getMemberMetaData().getAbsoluteFieldNumber());
                                            if (persMap != null && persMap.containsValue(mapRemoveOp.getValue()))
                                            {
                                                valNeedsRemoving = false;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                            if (valNeedsRemoving)
                            {
                                if (objectsToCascadeDelete == null)
                                {
                                    objectsToCascadeDelete = new ArrayList();
                                }
                                objectsToCascadeDelete.add(mapRemoveOp.getValue());
                            }
                        }
                    }
                }

                if (op instanceof SCOOperation)
                {
                    opsToIgnore.add(op);
                }
            }

            // Remove all SCO operations since we don't have backing store SCOs here
            queuedOperations.removeAll(opsToIgnore);

            if (objectsToCascadeDelete != null)
            {
                for (Object deleteObj : objectsToCascadeDelete)
                {
                    NucleusLogger.PERSISTENCE.debug("Initiating cascade delete of " + deleteObj);
                    ec.deleteObjectInternal(deleteObj);
                }
            }
        }
    }

    /**
     * Convenience optimisation checker to return if the current operation is ADD of an element that is
     * immediately REMOVED. Always leaves the iterator at the same position as starting
     * @param store The backing store
     * @param op The object provider
     * @param currentOper The current operation
     * @param listIter The iterator of operations
     * @return Whether this is an ADD that has a REMOVE of the same element immediately after
     */
    protected static boolean isAddFollowedByRemoveOnSameSCO(Store store, ObjectProvider op, Operation currentOper, ListIterator<Operation> listIter)
    {
        if (CollectionAddOperation.class.isInstance(currentOper))
        {
            // Optimisation to check that we aren't doing add+remove of the same element consecutively
            boolean addThenRemove = false;
            if (listIter.hasNext())
            {
                // Get next element
                Operation operNext = listIter.next();
                if (CollectionRemoveOperation.class.isInstance(operNext))
                {
                    Object value = CollectionAddOperation.class.cast(currentOper).getValue();
                    if (value == CollectionRemoveOperation.class.cast(operNext).getValue())
                    {
                        addThenRemove = true;
                        NucleusLogger.PERSISTENCE.info("Member " + store.getOwnerMemberMetaData().getFullFieldName() + 
                            " of " + StringUtils.toJVMIDString(op.getObject()) + " had an add then a remove of element " + 
                            StringUtils.toJVMIDString(value) + " - operations ignored");
                    }
                }

                // Go back
                listIter.previous();
            }
            return addThenRemove;
        }
        return false;
    }

    /**
     * Convenience optimisation checker to return if the current operation is REMOVE of an element that is
     * immediately ADDed. Always leaves the iterator at the same position as starting
     * @param store The backing store
     * @param op The object provider
     * @param currentOper The current operation
     * @param listIter The iterator of operations
     * @return Whether this is a REMOVE that has an ADD of the same element immediately after
     */
    protected static boolean isRemoveFollowedByAddOnSameSCO(Store store, ObjectProvider op, Operation currentOper, ListIterator<Operation> listIter)
    {
        if (CollectionRemoveOperation.class.isInstance(currentOper))
        {
            // Optimisation to check that we aren't doing add+remove of the same element consecutively
            boolean removeThenAdd = false;
            if (listIter.hasNext())
            {
                // Get next element
                Operation opNext = listIter.next();
                if (CollectionAddOperation.class.isInstance(opNext))
                {
                    Object value = CollectionRemoveOperation.class.cast(currentOper).getValue();
                    if (value == CollectionAddOperation.class.cast(opNext).getValue())
                    {
                        removeThenAdd = true;
                        NucleusLogger.PERSISTENCE.info("Member" + store.getOwnerMemberMetaData().getFullFieldName() + 
                            " of " + StringUtils.toJVMIDString(op.getObject()) + " had a remove then add of element " + 
                            StringUtils.toJVMIDString(value) + " - operations ignored");
                    }
                }

                // Go back
                listIter.previous();
            }
            return removeThenAdd;
        }
        return false;
    }

    /**
     * Convenience optimisation checker to return if the current operation is PUT of a key that is
     * immediately REMOVED. Always leaves the iterator at the same position as starting
     * @param store The backing store
     * @param op The object provider
     * @param currentOper The current operation
     * @param listIter The iterator of operations
     * @return Whether this is a PUT that has a REMOVE of the same key immediately after
     */
    protected static boolean isPutFollowedByRemoveOnSameSCO(Store store, ObjectProvider op, Operation currentOper, ListIterator<Operation> listIter)
    {
        if (MapPutOperation.class.isInstance(currentOper))
        {
            // Optimisation to check that we aren't doing put+remove of the same key consecutively
            boolean putThenRemove = false;
            if (listIter.hasNext())
            {
                // Get next element
                Operation operNext = listIter.next();
                if (MapRemoveOperation.class.isInstance(operNext))
                {
                    Object key = MapPutOperation.class.cast(currentOper).getKey();
                    if (key == MapRemoveOperation.class.cast(operNext).getKey())
                    {
                        putThenRemove = true;
                        NucleusLogger.PERSISTENCE.info("Member " + store.getOwnerMemberMetaData().getFullFieldName() + 
                            " of " + StringUtils.toJVMIDString(op.getObject()) + " had a put then a remove of key " + 
                            StringUtils.toJVMIDString(key) + " - operations ignored");
                    }
                }

                // Go back
                listIter.previous();
            }
            return putThenRemove;
        }
        return false;
    }
}