#!/usr/bin/env python3
import dataclasses
import importlib.util
import pathlib
import sys
import unittest

spec=importlib.util.spec_from_file_location('independent_policy',pathlib.Path(__file__).with_name('independent-control-policy.py'))
p=importlib.util.module_from_spec(spec);sys.modules[spec.name]=p;spec.loader.exec_module(p)


class IndependentAuthorityPolicyTest(unittest.TestCase):
    def setUp(self):
        self.active=p.Lease('campus',10,True);self.standby=p.Lease('campus',10,False)
        self.release=p.Lease('sg',11,True)
        self.transfer=p.Transfer('request','campus','sg',10,'TRANSFERRING',True)
    def decide(self,source=None,target=None,transfer=None):
        return p.decide('campus','sg',source or self.active,target or self.standby,transfer)
    def test_valid_active_snapshot_only_updates_foreign_standby(self):
        self.assertEqual(p.Action.MIRROR,self.decide())
    def test_expired_source_cannot_elect_standby(self):
        with self.assertRaisesRegex(p.Refused,'SOURCE_AUTHORITY_UNAVAILABLE'):
            self.decide(dataclasses.replace(self.active,live=False))
    def test_released_lease_without_committed_request_cannot_activate_target(self):
        with self.assertRaisesRegex(p.Refused,'COMMITTED_RELEASE_REQUIRED'):self.decide(self.release)
    def test_waiting_request_does_not_count_as_source_release(self):
        with self.assertRaisesRegex(p.Refused,'COMMITTED_RELEASE_REQUIRED'):
            self.decide(self.release,transfer=dataclasses.replace(self.transfer,state='WAITING'))
    def test_only_exact_quiescent_release_installs_new_authority(self):
        self.assertEqual(p.Action.INSTALL_RELEASE,self.decide(self.release,transfer=self.transfer))
        with self.assertRaisesRegex(p.Refused,'SOURCE_NOT_QUIESCENT'):
            self.decide(self.release,transfer=dataclasses.replace(self.transfer,quiescent=False))
    def test_two_local_authorities_refuse_every_copy_even_if_target_lease_expired(self):
        for live in [True,False]:
            with self.assertRaisesRegex(p.Refused,'TWO_AUTHORITIES_NO_COPY'):
                self.decide(target=p.Lease('sg',10,live))
    def test_duplicate_release_never_resets_acknowledged_target_state(self):
        self.assertEqual(p.Action.ALREADY_INSTALLED,self.decide(self.release,p.Lease('sg',11,True),self.transfer))
    def test_stale_peer_cannot_overwrite_newer_target(self):
        self.assertEqual(p.Action.STALE,self.decide(self.release,p.Lease('sg',12,False),self.transfer))
    def test_changed_target_snapshot_requires_reconciliation(self):
        with self.assertRaisesRegex(p.Refused,'TARGET_BASE_CHANGED'):
            self.decide(self.release,p.Lease('campus',9,True),self.transfer)
    def test_incorrect_release_chain_and_unknown_holder_are_rejected(self):
        with self.assertRaisesRegex(p.Refused,'TRANSFER_GENERATION_CHANGED'):
            self.decide(p.Lease('sg',12,True),transfer=self.transfer)
        with self.assertRaisesRegex(p.Refused,'TRANSFER_BINDING_CHANGED'):
            self.decide(self.release,transfer=dataclasses.replace(self.transfer,target='third'))
        with self.assertRaisesRegex(p.Refused,'UNKNOWN_AUTHORITY'):
            self.decide(p.Lease('third',11,True))


if __name__=='__main__':unittest.main()
