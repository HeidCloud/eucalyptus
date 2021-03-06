/*************************************************************************
 * Copyright 2009-2014 Eucalyptus Systems, Inc.
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
package com.eucalyptus.imaging;

import org.apache.log4j.Logger;

import com.eucalyptus.imaging.AbstractTaskScheduler.WorkerTask;
import com.eucalyptus.util.EucalyptusCloudException;

public class ImagingService {
  private static Logger LOG = Logger.getLogger( ImagingService.class );

  public PutInstanceImportTaskStatusResponseType PutInstanceImportTaskStatus( PutInstanceImportTaskStatusType request ) throws EucalyptusCloudException {
    LOG.debug(request);
    final PutInstanceImportTaskStatusResponseType reply = request.getReply( );
    reply.setCancelled(false);

    try{
      final String taskId = request.getImportTaskId();
      final String volumeId = request.getVolumeId();
      if(taskId==null || volumeId==null)
        throw new Exception("Task or volume id is null");
      
      ImagingTask imagingTask = null;

      try{
        imagingTask= ImagingTasks.lookup(taskId);
      }catch(final Exception ex){
        reply.setCancelled(true);
        throw new Exception("imaging task with "+taskId+" is not found");
      }
      
      if(ImportTaskState.CONVERTING.equals(imagingTask.getState())){
        //EXTANT, FAILED, DONE
        final WorkerTaskState workerState = WorkerTaskState.fromString(request.getStatus());
        if(WorkerTaskState.EXTANT.equals(workerState) || WorkerTaskState.DONE.equals(workerState)){
          try{
            final long bytesConverted= request.getBytesConverted();
            if(bytesConverted>0)
              ImagingTasks.updateBytesConverted(taskId, volumeId, bytesConverted);
          }catch(final Exception ex){
            LOG.warn("Failed to update bytes converted("+taskId+")");
          }
        }
        
        switch(workerState){
        case EXTANT:
            ;
          break;

        case DONE:
          try{
              ImagingTasks.updateVolumeStatus(imagingTask, volumeId, ImportTaskState.COMPLETED, null);
          }catch(final Exception ex){
            ImagingTasks.transitState(imagingTask, ImportTaskState.CONVERTING, 
                ImportTaskState.FAILED, "Failed to update volume's state");
            LOG.error("Failed to update volume's state", ex);
            break;
          }
          try{
            if(ImagingTasks.isConversionDone(imagingTask)){
              if(imagingTask instanceof InstanceImagingTask){
                ImagingTasks.transitState(imagingTask, ImportTaskState.CONVERTING, 
                    ImportTaskState.INSTANTIATING, null);
              }else{
                ImagingTasks.transitState(imagingTask, ImportTaskState.CONVERTING, 
                  ImportTaskState.COMPLETED, null);
              }
            }
          }catch(final Exception ex){
            LOG.error("Failed to update imaging task's state to completed", ex);
          }
          break;

        case FAILED:
          ImagingTasks.setState(imagingTask, ImportTaskState.FAILED, request.getStatusMessage());
          break;
        }
      }else{
        reply.setCancelled(true);
      }
    }catch(final Exception ex){
      LOG.warn("Failed to update the task's state", ex);
    }
    LOG.debug(reply);
    return reply;
  }

  public GetInstanceImportTaskResponseType GetInstanceImportTask( GetInstanceImportTaskType request ) throws EucalyptusCloudException {
    final GetInstanceImportTaskResponseType reply = request.getReply( );
    LOG.debug(request);
    try{
      final WorkerTask task = AbstractTaskScheduler.getScheduler().getTask();
      if(task!=null){
        reply.setImportTaskId(task.getTaskId());
        reply.setManifestUrl(task.getDownloadManifestUrl());
        reply.setVolumeId(task.getVolumeId());
      }
    }catch(final Exception ex){
      LOG.error("Failed to schedule a task", ex);
    }
    LOG.debug(reply);
    return reply;
  }
}
