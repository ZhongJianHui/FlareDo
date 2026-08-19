package dev.dimension.flare.data.network.discourse

/**
 * Self-authored Stage 6 transport fixtures.
 *
 * Every identifier, username, timestamp, title, and payload below is synthetic. These fixtures are
 * intentionally small enough to audit and contain no response copied from Linux.do, fluxdo, or a
 * real account. Unknown fields exercise forward compatibility while omitted optional fields verify
 * that the client does not invent required routing or security identity.
 */
internal object Stage6ApiContractFixtures {
    const val SEARCH_PAGE_ONE: String =
        """
        {
          "posts": [
            {
              "id": 6101,
              "topic_id": 6201,
              "post_number": 1,
              "username": "fixture-author",
              "blurb": "Self-authored search hit one",
              "future_post_field": {"ignored": true}
            },
            {
              "id": 6102,
              "topic_id": 6202,
              "post_number": 3,
              "username": "fixture-author",
              "blurb": "Self-authored overlapping hit"
            }
          ],
          "topics": [
            {"id": 6201, "title": "Fixture topic one", "slug": "fixture-topic-one"},
            {"id": 6202, "title": "Fixture overlap topic", "slug": "fixture-overlap-topic"}
          ],
          "grouped_search_result": {
            "term": "contract query",
            "more_posts": true,
            "more_full_page_results": true,
            "post_ids": [6101, 6102],
            "future_grouped_field": "ignored"
          },
          "future_search_field": ["ignored"]
        }
        """

    const val SEARCH_PAGE_TWO_WITH_OVERLAP: String =
        """
        {
          "posts": [
            {
              "id": 6102,
              "topic_id": 6202,
              "post_number": 3,
              "username": "fixture-author",
              "blurb": "Deliberate overlap from page one"
            },
            {
              "id": 6103,
              "topic_id": 6203,
              "post_number": 2,
              "username": "fixture-reviewer",
              "blurb": "Self-authored search hit three"
            }
          ],
          "topics": [
            {"id": 6202, "title": "Fixture overlap topic", "slug": "fixture-overlap-topic"},
            {"id": 6203, "title": "Fixture topic three", "slug": "fixture-topic-three"}
          ],
          "grouped_search_result": {
            "term": "contract query",
            "more_posts": false,
            "more_full_page_results": false,
            "post_ids": [6102, 6103]
          }
        }
        """

    const val USER_PROFILE: String =
        """
        {
          "user": {
            "id": 7101,
            "username": "fixture-member",
            "name": "Fixture Member",
            "bio_cooked": "<p>Self-authored profile fixture.</p>",
            "future_profile_field": {"ignored": 1}
          },
          "badges": [],
          "future_profile_envelope": true
        }
        """

    const val USER_SUMMARY: String =
        """
        {
          "user_summary": {
            "likes_given": 4,
            "likes_received": 9,
            "post_count": 12,
            "top_replies": [
              {
                "id": 7201,
                "topic_id": 6201,
                "post_number": 4,
                "title": "Fixture topic one",
                "slug": "fixture-topic-one",
                "future_reply_field": false
              }
            ],
            "future_summary_field": "ignored"
          },
          "topics": [],
          "users": [],
          "future_summary_envelope": {"ignored": true}
        }
        """

    const val USER_ACTIVITY: String =
        """
        {
          "user_actions": [
            {
              "action_type": 5,
              "created_at": "2026-01-02T03:04:05.000Z",
              "user_id": 7101,
              "username": "fixture-member",
              "topic_id": 6201,
              "post_id": 7201,
              "post_number": 4,
              "slug": "fixture-topic-one",
              "title": "Fixture topic one",
              "excerpt": "Self-authored activity excerpt",
              "future_activity_field": [1, 2]
            }
          ],
          "future_activity_envelope": "ignored"
        }
        """

    const val NOTIFICATIONS_FIRST_PAGE: String =
        """
        {
          "notifications": [
            {
              "id": 8103,
              "user_id": 7101,
              "notification_type": 5,
              "topic_id": 6203,
              "post_number": 2,
              "data": {"topic_title": "Fixture topic three", "future_data": 1},
              "future_notification_field": true
            },
            {
              "id": 8102,
              "user_id": 7101,
              "notification_type": 6,
              "topic_id": 6202,
              "post_number": 3
            }
          ],
          "total_rows_notifications": 3,
          "seen_notification_id": 8101,
          "load_more_notifications": "/notifications?offset=2&limit=2",
          "future_notification_envelope": {"ignored": true}
        }
        """

    const val NOTIFICATIONS_SECOND_PAGE_WITH_OVERLAP: String =
        """
        {
          "notifications": [
            {
              "id": 8102,
              "user_id": 7101,
              "notification_type": 6,
              "topic_id": 6202,
              "post_number": 3
            },
            {
              "id": 8101,
              "user_id": 7101,
              "notification_type": 9,
              "topic_id": 6201,
              "post_number": 4,
              "read": true
            }
          ],
          "total_rows_notifications": 3,
          "seen_notification_id": 8101
        }
        """

    const val USER_MISSING_USERNAME: String = """{"user":{"id":7199}}"""
    const val SUMMARY_MISSING_REQUIRED_ENVELOPE: String = """{"future_summary_field":true}"""
    const val ACTIVITY_MISSING_CREATED_AT: String =
        """{"user_actions":[{"action_type":5,"future_activity_field":true}]}"""
    const val NOTIFICATION_MISSING_TYPE: String =
        """{"notifications":[{"id":8199,"user_id":7101,"future_notification_field":true}]}"""
    const val CSRF: String = """{"csrf":"fixture-csrf-token"}"""
}
