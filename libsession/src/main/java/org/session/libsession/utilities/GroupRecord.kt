package org.session.libsession.utilities

import android.text.TextUtils
import java.io.IOException
import java.util.LinkedList

class GroupRecord(
    val encodedId: String, val title: String, members: String?, val avatar: ByteArray?,
    val avatarId: Long?, val avatarKey: ByteArray?, val avatarContentType: String?,
    val relay: String?, val isActive: Boolean, val avatarDigest: ByteArray?, val isMms: Boolean,
    val url: String?, admins: String?, val formationTimestamp: Long, val updatedTimestamp: Long
) {
    var members: List<Address> = LinkedList<Address>()
    var admins: List<Address> = LinkedList<Address>()

    fun getId(): ByteArray {
        return try {
            GroupUtil.getDecodedGroupIDAsData(encodedId)
        } catch (ioe: IOException) {
            throw AssertionError(ioe)
        }
    }

    val isCommunity: Boolean
        get() = Address.fromSerialized(encodedId).isCommunity
    val isLegacyGroup: Boolean
        get() = Address.fromSerialized(encodedId).isLegacyGroup
    val isGroupV2: Boolean
        get() = Address.fromSerialized(encodedId).isGroupV2

    init {
        if (!TextUtils.isEmpty(members)) {
            this.members = Address.fromSerializedList(members!!, ',')
        }
        if (!TextUtils.isEmpty(admins)) {
            this.admins = Address.fromSerializedList(admins!!, ',')
        }
    }
}
