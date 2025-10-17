package it.fabiodirauso.shutappchat.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import it.fabiodirauso.shutappchat.R
import it.fabiodirauso.shutappchat.model.GroupMemberEntity
import it.fabiodirauso.shutappchat.model.GroupRole

/**
 * Adapter per la lista membri del gruppo
 */
class GroupMembersAdapter(
    private val currentUserId: Long,
    private var isCurrentUserAdmin: Boolean,
    private val onRemoveMember: (GroupMemberEntity) -> Unit,
    private val onChangeRole: (GroupMemberEntity) -> Unit
) : RecyclerView.Adapter<GroupMembersAdapter.MemberViewHolder>() {
    
    private var members: List<GroupMemberEntity> = emptyList()
    
    fun updateMembers(newMembers: List<GroupMemberEntity>) {
        members = newMembers
        notifyDataSetChanged()
    }
    
    fun updateAdminStatus(isAdmin: Boolean) {
        isCurrentUserAdmin = isAdmin
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group_member, parent, false)
        return MemberViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
        holder.bind(members[position])
    }
    
    override fun getItemCount(): Int = members.size
    
    inner class MemberViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val memberIcon: ImageView = itemView.findViewById(R.id.imageViewMemberIcon)
        private val memberName: TextView = itemView.findViewById(R.id.textViewMemberName)
        private val memberRole: TextView = itemView.findViewById(R.id.textViewMemberRole)
        private val buttonChangeRole: ImageButton = itemView.findViewById(R.id.buttonChangeRole)
        private val buttonRemove: ImageButton = itemView.findViewById(R.id.buttonRemove)
        
        fun bind(member: GroupMemberEntity) {
            memberName.text = member.displayName
            
            // Role badge
            if (member.role == GroupRole.ADMIN) {
                memberRole.text = " Admin"
                memberRole.visibility = View.VISIBLE
            } else {
                memberRole.visibility = View.GONE
            }
            
            // Action buttons (only visible if current user is admin)
            val showActions = isCurrentUserAdmin && member.userId != currentUserId
            buttonChangeRole.visibility = if (showActions) View.VISIBLE else View.GONE
            buttonRemove.visibility = if (showActions) View.VISIBLE else View.GONE
            
            // Change role button
            if (member.role == GroupRole.ADMIN) {
                buttonChangeRole.setImageResource(R.drawable.ic_arrow_down)
                buttonChangeRole.contentDescription = "Rendi membro"
            } else {
                buttonChangeRole.setImageResource(R.drawable.ic_arrow_up)
                buttonChangeRole.contentDescription = "Rendi admin"
            }
            
            buttonChangeRole.setOnClickListener {
                onChangeRole(member)
            }
            
            buttonRemove.setOnClickListener {
                onRemoveMember(member)
            }
        }
    }
}
