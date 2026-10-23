#!/usr/bin/env python3
"""Pure policy for two independent manual-only control databases.

The privileged transport/importer must validate cluster IDs, physical installs,
table allowlists, state-file hashes and a committed source snapshot separately.
This module grants no SQL, filesystem or network access.
"""
from dataclasses import dataclass
from enum import Enum


class Refused(RuntimeError):
    pass


class Action(Enum):
    MIRROR = 'MIRROR_STANDBY'
    INSTALL_RELEASE = 'INSTALL_RELEASED_AUTHORITY'
    ALREADY_INSTALLED = 'ALREADY_INSTALLED'
    STALE = 'STALE_SNAPSHOT'


@dataclass(frozen=True)
class Lease:
    holder: str
    generation: int
    live: bool


@dataclass(frozen=True)
class Transfer:
    request_id: str
    source: str
    target: str
    expected_generation: int
    state: str
    # Established by the application before committing its release.
    quiescent: bool


def decide(source_id, target_id, source, target, transfer=None):
    """Never elect on timeout. A transfer needs a committed source release.

    Even an expired local lease belonging to the target is not permission to
    overwrite it with a peer snapshot: it might be recovering its own authority.
    """
    if not source_id or not target_id or source_id == target_id:
        raise Refused('CONTROLLER_IDENTITY_REQUIRED')
    if min(source.generation, target.generation) < 1:
        raise Refused('CONTROL_GENERATION_REQUIRED')
    if source.holder not in (source_id, target_id) or target.holder not in (source_id, target_id):
        raise Refused('UNKNOWN_AUTHORITY')
    if source.generation < target.generation:
        return Action.STALE
    if target.holder == target_id:
        if source.holder == source_id and source.live:
            raise Refused('TWO_AUTHORITIES_NO_COPY')
        if source.holder == target_id and source.generation <= target.generation:
            return Action.ALREADY_INSTALLED
        raise Refused('TARGET_AUTHORITY_MUST_NOT_BE_OVERWRITTEN')
    if source.holder == source_id:
        if not source.live:
            raise Refused('SOURCE_AUTHORITY_UNAVAILABLE')
        if source.generation == target.generation and source.holder != target.holder:
            raise Refused('SAME_GENERATION_DIFFERENT_AUTHORITY')
        return Action.MIRROR
    if transfer is None or not transfer.request_id or transfer.state != 'TRANSFERRING':
        raise Refused('COMMITTED_RELEASE_REQUIRED')
    if (transfer.source, transfer.target) != (source_id, target_id):
        raise Refused('TRANSFER_BINDING_CHANGED')
    if source.generation != transfer.expected_generation + 1:
        raise Refused('TRANSFER_GENERATION_CHANGED')
    if target.generation != transfer.expected_generation or target.holder != source_id:
        raise Refused('TARGET_BASE_CHANGED')
    if not transfer.quiescent:
        raise Refused('SOURCE_NOT_QUIESCENT')
    return Action.INSTALL_RELEASE
