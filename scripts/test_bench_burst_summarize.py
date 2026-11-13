"""Check the Kubernetes timestamp boundaries used by the burst report."""
import unittest
from bench_burst_summarize import phase_times


class PhaseTimesTest(unittest.TestCase):
    def test_uses_conditions_first_init_and_named_app(self):
        pod = {
            'metadata': {'creationTimestamp': '2026-01-01T00:00:00Z'},
            'spec': {'initContainers': [{'name': 'install'}, {'name': 'fetch'}]},
            'status': {
                'conditions': [
                    {'type': 'PodScheduled', 'status': 'True', 'lastTransitionTime': '2026-01-01T00:00:02Z'},
                    {'type': 'Initialized', 'status': 'True', 'lastTransitionTime': '2026-01-01T00:00:10Z'},
                    {'type': 'Ready', 'status': 'True', 'lastTransitionTime': '2026-01-01T00:00:20Z'},
                ],
                'initContainerStatuses': [
                    {'name': 'fetch', 'state': {'terminated': {'startedAt': '2026-01-01T00:00:06Z'}}},
                    {'name': 'install', 'state': {'terminated': {'startedAt': '2026-01-01T00:00:03Z'}}},
                ],
                'containerStatuses': [
                    {'name': 'sidecar', 'state': {'running': {'startedAt': '2026-01-01T00:00:11Z'}}},
                    {'name': 'app', 'state': {'running': {'startedAt': '2026-01-01T00:00:12Z'}}},
                ],
            },
        }
        phases = phase_times(pod)
        self.assertEqual([phases[k] for k in ('scheduling_s', 'init_install_fetch_s',
                         'initialized_to_app_container_start_s', 'app_container_start_to_ready_s')], [2, 7, 2, 8])

    def test_no_init_container_does_not_invent_an_init_duration(self):
        pod = {'metadata': {'creationTimestamp': '2026-01-01T00:00:00Z'}, 'spec': {}, 'status': {}}
        self.assertIsNone(phase_times(pod)['init_install_fetch_s'])
        self.assertIsNone(phase_times(pod)['app_container_start_to_ready_s'])


if __name__ == '__main__':
    unittest.main()
