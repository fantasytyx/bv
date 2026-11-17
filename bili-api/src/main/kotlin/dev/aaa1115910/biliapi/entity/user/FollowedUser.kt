package dev.aaa1115910.biliapi.entity.user

data class FollowedUser(
    val mid: Long,
    val name: String,
    val avatar: String,
    val sign: String
) {
    companion object {
        fun fromHttpFollowedUser(followedUser: dev.aaa1115910.biliapi.http.entity.user.UserFollowData.FollowedUser) =
            FollowedUser(
                mid = followedUser.mid,
                name = followedUser.uname,
                avatar = followedUser.face,
                sign = followedUser.sign
            )
    }
}

/** 一页关注列表；`hasMore` 由总数推导，缓存只有前缀时仍然能继续翻页 */
data class FollowedUserPage(
    val users: List<FollowedUser>,
    val total: Int,
    val hasMore: Boolean
) {
    companion object {
        fun from(users: List<FollowedUser>, page: Int, pageSize: Int, total: Int) = FollowedUserPage(
            users = users,
            total = total,
            hasMore = page * pageSize < total
        )
    }
}