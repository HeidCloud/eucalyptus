/*************************************************************************
 * Copyright 2009-2013 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 ************************************************************************/

package com.eucalyptus.objectstorage;

import java.util.List;

import com.eucalyptus.auth.principal.User;
import com.eucalyptus.entities.TransactionException;
import com.eucalyptus.objectstorage.entities.Bucket;
import com.eucalyptus.objectstorage.entities.ObjectEntity;
import com.eucalyptus.objectstorage.entities.PartEntity;
import com.eucalyptus.objectstorage.exceptions.s3.S3Exception;
import com.eucalyptus.objectstorage.msgs.CompleteMultipartUploadResponseType;
import com.eucalyptus.objectstorage.msgs.ObjectStorageDataResponseType;
import com.eucalyptus.objectstorage.msgs.SetRESTObjectAccessControlPolicyResponseType;
import com.eucalyptus.storage.msgs.s3.AccessControlPolicy;

/**
 * Interface for interacting with object metadata (not content directly)
 * @author zhill
 *
 */

/*
 * State of an object:
 * (key, uuid, lastModified, versionId, isDeleted, deletedDate)
 * 
 * Possible permutations:
 * 
 * A: (key=null, ...)
 * invalid. Key must always be set.
 * 
 * B: (key=non-null, uuid=null, lastModified=null, versionId=null, isDeleted=false, deletedTimestamp=null)
 * Initialized but not persisted, not-deleted, not confirmed to exist in back-end. This is
 * an error state for an Object if found in the DB in this state. uuid must be set on any
 * persisted object metadata
 * 
 * C: (key=non-null, uuid=non-null, lastModified=null, versionId="null", isDeleted=false, deletedTimestamp=null)
 * Valid for an in-progress upload to the back-end
 *
 * D: (key=non-null, uuid=non-null, lastModified=non-null, versionId="null", isDeleted=false, deletedTimestamp=null)
 * A valid persisted object that was confirmed to be stored in back-end.
 * Versioning is not enabled on the bucket (or versionId would be set)
 *
 * E: (key=non-null, uuid=non-null, lastModified=non-null, versionId=non-null, isDeleted=false, deletedTimestamp=null)
 * A valid persisted object that was confirmed to be stored in back-end.
 * Versioning enabled bucket (at time of PUT). versionId may = uuid, but not necessarily
 *
 * F: (key=non-null, uuid=non-null, lastModified=non-null, versionId="null", isDeleted=true, deletedTimestamp=null)
 * A valid "delete marker" for a version-suspended object. This may be the
 * "latest" object depending on the lastModified date and other records.
 * 
 * G: (key=non-null, uuid=non-null, lastModified=non-null, versionId=non-null, isDeleted=true, deletedTimestamp=null)
 * A "delete marker" entry with the given versionId on a version-enabled (or suspended) bucket. No corresponding backend resource guaranteed,
 * but may exist (e.g. another delete marker for backend)
 * 
 * H: (...., deletedTimestamp=non-null)
 * Record marked for actual deletion. This record will be removed from the db upon confirmation that any backend resource is removed as well.
 * 
 * 
 */
public interface ObjectManager {
	
	public abstract void start() throws Exception;
	public abstract void stop() throws Exception;
	
	/**
	 * Count of objects in the given bucket
	 * @param bucketName
	 * @return
	 * @throws TransactionException
	 */
	public abstract long countRawEntities(Bucket bucket) throws Exception;

	/**
	 * Does specified object exist
	 * @param bucketName
	 * @param objectKey
	 * @param versionId
	 * @return
	 * @throws TransactionException
	 */
	public abstract <T,F> boolean exists(Bucket bucket, String objectKey, String versionId,  CallableWithRollback<T, F> resourceModifier) throws Exception;
	
	/**
	 * Get the entity record, not the content
	 * @param bucketName
	 * @param objectKey
	 * @param versionId
	 * @return
	 * @throws TransactionException
	 */
	public abstract ObjectEntity get(Bucket bucket, String objectKey, String versionId) throws Exception;
	
	/**
	 * List the objects in the given bucket
	 * @param bucketName
	 * @param maxRecordCount
	 * @param prefix
	 * @param delimiter
	 * @param startKey
	 * @return
	 * @throws TransactionException
	 */
	public abstract PaginatedResult<ObjectEntity> listPaginated(Bucket bucket, int maxRecordCount, String prefix, String delimiter, String startKey) throws Exception;

	/**
	 * List the object versions in the given bucket
	 * @param bucketName
	 * @param maxKeys
	 * @param prefix
	 * @param delimiter
	 * @param startKey
	 * @param startVersionId
	 * @param includeDeleteMarkers
	 * @return
	 * @throws TransactionException
	 */
	public abstract PaginatedResult<ObjectEntity> listVersionsPaginated(Bucket bucket, int maxKeys, String prefix, String delimiter, String startKey, String startVersionId, boolean includeDeleteMarkers) throws Exception;
	
	/**
	 * Delete the object entity
	 * @param bucketName
	 * @param objectKey
	 * @param versionId
	 * @param resourceModifier
	 * @throws S3Exception
	 * @throws TransactionException
	 */
	public abstract void delete(Bucket bucket, ObjectEntity objectToDelete, User requestUser) throws Exception;
	
	/**
	 * Uses the provided supplier to get a versionId since that is dependent on the bucket state
	 * @param bucketName
	 * @param object
	 * @param versionIdSupplier
	 */
	public abstract <T extends ObjectStorageDataResponseType, F> T create(Bucket bucket, ObjectEntity object, CallableWithRollback<T,F> resourceModifier) throws Exception;

    /**
     * Create a pending object record that will be finalized later
     * @param bucket
     * @param object
     * @throws Exception
     */
    public abstract ObjectEntity createPending(Bucket bucket, ObjectEntity object) throws Exception;

    /**
     * Creates a PartEntity that will be committed after resourceModifier is successfully executed
     * @param bucket
     * @param object
     * @param resourceModifier
     * @return
     * @throws Exception
     */
    public abstract <T extends ObjectStorageDataResponseType, F> T createPart(Bucket bucket, PartEntity object, CallableWithRollback<T,F> resourceModifier) throws Exception;

    /**
     * Update a saved object entity
     * @param bucket
     * @param object
     * @throws Exception
     */
    public abstract void updateObject(Bucket bucket, ObjectEntity object) throws Exception;

    public abstract <T extends SetRESTObjectAccessControlPolicyResponseType, F> T setAcp(ObjectEntity object, AccessControlPolicy acp, CallableWithRollback<T, F> resourceModifier) throws Exception;
	
	/**
	 * Gets all object entities that are determined to be failed or deleted.
	 * Failure detection is based on timestamp comparision is limited by the
	 * {@link ObjectStorageGatewayGlobalConfiguration.failedPutTimeoutHours}. Normal failure
	 * cases are handled by marking the record for deletion, but if the OSG itself
	 * fails during an upload the timestamp is used
	 * @return
	 * @throws Exception
	 */
	public abstract List<ObjectEntity> getFailedOrDeleted() throws Exception;
	
	/**
	 * Fix an object history if needed. Scans the sorted object records and marks
	 * latest as well as marking contiguous null-versioned records for deletion to
	 * remove contiguous nulls in the version history
	 * 
	 * @param bucketName
	 * @param objectKey
	 * @throws Exception
	 */
	public abstract void doFullRepair(Bucket bucket, String objectKey) throws Exception;
	
	/**
	 * Returns a count of "valid" objects in the bucket. Valid means visible to user, not-deleting, and not-pending/failed.
	 * @param bucket
	 * @return
	 */
	public abstract long countValid(Bucket bucket) throws Exception ;

    /**
     * Returns the total size of all successfully uploaded parts for a specific uploadId
     * @param bucket
     * @param objectKey
     * @param uploadId
     * @return
     * @throws Exception
     */
    public abstract long getUploadSize(Bucket bucket, String objectKey, String uploadId) throws Exception;

    /**
     * Get entity that corresponds to the specified uploadId
     * @param bucket
     * @param uploadId
     * @return
     * @throws Exception
     */
    public abstract ObjectEntity getObject(Bucket bucket, String objectKey, String uploadId) throws Exception;

    /**
     * Get all objects that have a pending multipart upload
     * @param bucket
     * @return
     * @throws Exception
     */
    public abstract ObjectEntity getObjects(Bucket bucket) throws Exception;

    /**
     * Call operation and update entity on success
     * @param bucket
     * @param object
     * @param resourceModifier
     * @param <T>
     * @param <F>
     * @return
     * @throws Exception
     */
    public abstract <T extends ObjectStorageDataResponseType, F> T merge(Bucket bucket, ObjectEntity object, CallableWithRollback<T,F> resourceModifier) throws Exception;

    /**
     * Remove persisted data on the OSG on a complete or abort upload
     * @param bucket
     * @param uploadId
     * @throws Exception
     */
    public abstract void removeParts(Bucket bucket, String uploadId) throws Exception;

    /**
     * Return entities corresponding to completed parts
     * @param bucket
     * @param objectKey
     * @param uploadId
     * @return
     * @throws Exception
     */
    public List<PartEntity> getParts(Bucket bucket, String objectKey, String uploadId) throws Exception;

    /**
     * Get the list of parts that are marked for deletion
     * @return
     * @throws Exception
     */
    public List<PartEntity> getDeletedParts() throws Exception;

        /**
         * Return paginated list of object entities that represent parts given an upload ID and other specified criteria
         * @param bucket
         * @param objectKey
         * @param uploadId
         * @param partNumberMarker
         * @param maxParts
         * @return
         * @throws Exception
         */
    public PaginatedResult<PartEntity> listPartsForUpload(final Bucket bucket,
                                                   final String objectKey,
                                                   final String uploadId,
                                                   final Integer partNumberMarker,
                                                   final Integer maxParts) throws Exception;


    /**
     * Return paginated list of object entities indicating uploads in progress given a bucket
     * This method and {@link #listVersionsPaginated(com.eucalyptus.objectstorage.entities.Bucket, int, String, String, String, String, boolean)}
     * are similar with a few differences: we are listing "incomplete" objects with multipart uploads in progress
     * and there is not concept of a delete marker
     * @param bucket
     * @param maxUploads
     * @param prefix
     * @param delimiter
     * @param keyMarker
     * @param uploadIdMarker
     * @return
     * @throws Exception
     */
    public PaginatedResult<ObjectEntity> listParts(final Bucket bucket,
                                                   int maxUploads,
                                                   String prefix,
                                                   String delimiter,
                                                   String keyMarker,
                                                   String uploadIdMarker) throws Exception;
}
