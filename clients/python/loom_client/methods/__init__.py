"""Method groups, one module per Java ``*Methods`` interface.

Each class here is a mixin of plain request-building methods with no state of its
own; :class:`loom_client.client.LoomClient` inherits all of them and supplies the
``_get``/``_post``/``_put``/``_patch``/``_delete``/``_upload``/``_download`` helpers
they call.

Keeping the Java grouping means a rename or a new endpoint lands in exactly one file
on both sides, and the parity test can compare the two trees mechanically. It does
mean the grouping occasionally cuts across paths -- ``tag_asset`` lives in
:class:`~loom_client.methods.tag.TagMethods` but hits ``/assets/.../tags`` -- which is
the Java layout, kept deliberately.
"""

from __future__ import annotations

from .annotation import AnnotationMethods
from .asset import AssetMethods
from .asset_binary import AssetBinaryMethods
from .asset_component import AssetComponentMethods
from .asset_location import AssetLocationMethods
from .asset_pool import AssetPoolMethods
from .attachment import AttachmentMethods
from .authentication import AuthenticationMethods
from .blacklist import BlacklistMethods
from .chat import ChatMethods
from .cluster import ClusterMethods
from .collection import CollectionMethods
from .comment import CommentMethods
from .dedup_group import DedupGroupMethods
from .detection import DetectionMethods
from .embedding import EmbeddingMethods
from .fingerprint_comp import FingerprintCompMethods
from .graphql import GraphQLMethods
from .group import GroupMethods
from .health import HealthMethods
from .info import InfoMethods
from .json_comp import JsonCompMethods
from .library import LibraryMethods
from .db_integrity import DbIntegrityMethods
from .metrics import MetricsMethods
from .node_result import NodeResultMethods
from .node_run import NodeRunMethods
from .person import PersonMethods
from .pipeline import PipelineMethods
from .reaction import ReactionMethods
from .role import RoleMethods
from .search import SearchMethods
from .search_index import SearchIndexMethods
from .segment_comp import SegmentCompMethods
from .similarity import SimilarityMethods
from .skill import SkillMethods
from .space import SpaceMethods
from .tag import TagMethods
from .notification import NotificationMethods
from .task import TaskMethods
from .token import TokenMethods
from .transcript import TranscriptMethods
from .user import UserMethods

#: Every method group, in the order LoomClient inherits them. Mirrors the interfaces
#: composed by the Java ``ClientMethods``.
ALL_METHOD_GROUPS: tuple[type, ...] = (
    AnnotationMethods,
    AssetBinaryMethods,
    AssetComponentMethods,
    AssetLocationMethods,
    AssetMethods,
    AssetPoolMethods,
    AttachmentMethods,
    AuthenticationMethods,
    BlacklistMethods,
    ChatMethods,
    ClusterMethods,
    CollectionMethods,
    CommentMethods,
    DedupGroupMethods,
    DetectionMethods,
    EmbeddingMethods,
    FingerprintCompMethods,
    GraphQLMethods,
    GroupMethods,
    HealthMethods,
    InfoMethods,
    JsonCompMethods,
    LibraryMethods,
    MetricsMethods,
    DbIntegrityMethods,
    NodeResultMethods,
    NodeRunMethods,
    PersonMethods,
    PipelineMethods,
    ReactionMethods,
    RoleMethods,
    SearchIndexMethods,
    SearchMethods,
    SegmentCompMethods,
    SimilarityMethods,
    SkillMethods,
    SpaceMethods,
    TagMethods,
    NotificationMethods,
    TaskMethods,
    TokenMethods,
    TranscriptMethods,
    UserMethods,
)

__all__ = [
    "ALL_METHOD_GROUPS",
    "AnnotationMethods",
    "AssetBinaryMethods",
    "AssetComponentMethods",
    "AssetLocationMethods",
    "AssetMethods",
    "AssetPoolMethods",
    "AttachmentMethods",
    "AuthenticationMethods",
    "BlacklistMethods",
    "ChatMethods",
    "ClusterMethods",
    "CollectionMethods",
    "CommentMethods",
    "DedupGroupMethods",
    "DetectionMethods",
    "EmbeddingMethods",
    "FingerprintCompMethods",
    "GraphQLMethods",
    "GroupMethods",
    "HealthMethods",
    "InfoMethods",
    "JsonCompMethods",
    "LibraryMethods",
    "MetricsMethods",
    "DbIntegrityMethods",
    "NodeResultMethods",
    "NodeRunMethods",
    "PersonMethods",
    "PipelineMethods",
    "ReactionMethods",
    "RoleMethods",
    "SearchIndexMethods",
    "SearchMethods",
    "SegmentCompMethods",
    "SimilarityMethods",
    "SkillMethods",
    "SpaceMethods",
    "TagMethods",
    "NotificationMethods",
    "TaskMethods",
    "TokenMethods",
    "TranscriptMethods",
    "UserMethods",
]
