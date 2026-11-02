import time
import unittest
from unittest import mock

from fixed_gpu_client import BusinessStream


class GeneratorCloseTest(unittest.TestCase):
    def make_stream(self):
        client = mock.Mock()
        client.cancel_and_observe.return_value = {'status':'release_unknown','operationId':'a'*32}
        stream = BusinessStream(client,'a'*32,time.monotonic()+2,{},None)
        stream.response, stream.connection = mock.Mock(status=200), mock.Mock()
        stream.response.getheader.return_value = 'text/event-stream'
        stream.response.isclosed.return_value = False
        stream.response.read1.return_value = b'data: {"choices":[{"delta":{"content":"a"}}]}\n\n'
        generator = stream.read()
        self.assertIn(b'"a"', next(generator))
        return client,stream,generator

    def test_normal_generator_close_cancels_once(self):
        client,stream,generator = self.make_stream()
        self.assertIsNone(generator.close()); self.assertIsNone(generator.close())
        client.cancel_and_observe.assert_called_once_with('a'*32,{})
        self.assertEqual('release_unknown',stream.exit_receipt['status'])

    def test_generator_close_reports_resource_close_error(self):
        client,stream,generator = self.make_stream()
        stream.response.close.side_effect = OSError('response-close')
        connection = stream.connection
        with self.assertRaisesRegex(OSError,'response-close'): generator.close()
        connection.close.assert_called_once()
        client.cancel_and_observe.assert_called_once_with('a'*32,{})
        self.assertEqual('release_unknown',stream.exit_receipt['status'])
        self.assertIsNone(generator.close())
        client.cancel_and_observe.assert_called_once()

    def test_generator_close_reports_observation_error_and_keeps_unknown(self):
        client,stream,generator = self.make_stream()
        client.cancel_and_observe.side_effect = OSError('observation-failed')
        with self.assertRaisesRegex(OSError,'observation-failed'): generator.close()
        client.cancel_and_observe.assert_called_once()
        self.assertEqual({'status':'release_unknown','operationId':'a'*32},stream.exit_receipt)

    def test_real_generator_error_remains_primary_if_cleanup_fails(self):
        client,stream,generator = self.make_stream()
        stream.response.close.side_effect = OSError('response-close')
        with self.assertRaisesRegex(ValueError,'consumer-error') as caught:
            generator.throw(ValueError('consumer-error'))
        self.assertEqual('response-close',str(caught.exception.__cause__))
        client.cancel_and_observe.assert_called_once()
        self.assertEqual('release_unknown',stream.exit_receipt['status'])


if __name__ == '__main__': unittest.main()
