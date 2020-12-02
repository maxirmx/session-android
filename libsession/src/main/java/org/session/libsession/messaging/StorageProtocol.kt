package org.session.libsession.messaging

import org.session.libsession.messaging.jobs.AttachmentUploadJob
import org.session.libsession.messaging.jobs.Job
import org.session.libsession.messaging.jobs.MessageSendJob
import org.session.libsession.messaging.opengroups.OpenGroup

import org.session.libsignal.libsignal.ecc.ECKeyPair
import org.session.libsignal.libsignal.ecc.ECPrivateKey

interface StorageProtocol {

    // General
    fun getUserPublicKey(): String?
    fun getUserKeyPair(): ECKeyPair?
    fun getUserDisplayName(): String?
    fun getUserProfileKey(): ByteArray?
    fun getUserProfilePictureURL(): String?

    // Shared Sender Keys
    fun getClosedGroupPrivateKey(publicKey: String): ECPrivateKey?
    fun isClosedGroup(publicKey: String): Boolean

    // Jobs
    fun persist(job: Job)
    fun markJobAsSucceeded(job: Job)
    fun markJobAsFailed(job: Job)
    fun getAllPendingJobs(type: String): List<Job>
    fun getAttachmentUploadJob(attachmentID: String): AttachmentUploadJob?
    fun getMessageSendJob(messageSendJobID: String): MessageSendJob?
    fun resumeMessageSendJobIfNeeded(messageSendJobID: String)
    fun isJobCanceled(job: Job): Boolean

    // Authorization
    fun getAuthToken(server: String): String?
    fun setAuthToken(server: String, newValue: String?)
    fun removeAuthToken(server: String)

    // Open Groups
    fun getOpenGroup(threadID: String): OpenGroup?
    fun getThreadID(openGroupID: String): String?

    // Open Group Public Keys
    fun getOpenGroupPublicKey(server: String): String?
    fun setOpenGroupPublicKey(server: String, newValue: String)

    // Last Message Server ID
    fun getLastMessageServerID(group: Long, server: String): Long?
    fun setLastMessageServerID(group: Long, server: String, newValue: Long)
    fun removeLastMessageServerID(group: Long, server: String)

    // Last Deletion Server ID
    fun getLastDeletionServerID(group: Long, server: String): Long?
    fun setLastDeletionServerID(group: Long, server: String, newValue: Long)
    fun removeLastDeletionServerID(group: Long, server: String)

    // Open Group Metadata
    fun setUserCount(group: Long, server: String, newValue: Int)
    fun setOpenGroupProfilePictureURL(group: Long, server: String, newValue: String)
    fun getOpenGroupProfilePictureURL(group: Long, server: String): String?
    fun updateTitle(groupID: String, newValue: String)
    fun updateProfilePicture(groupID: String, newValue: ByteArray)




    fun getSessionRequestSentTimestamp(publicKey: String): Long?
    fun setSessionRequestSentTimestamp(publicKey: String, newValue: Long)
    fun getSessionRequestProcessedTimestamp(publicKey: String): Long?
    fun setSessionRequestProcessedTimestamp(publicKey: String, newValue: Long)
}