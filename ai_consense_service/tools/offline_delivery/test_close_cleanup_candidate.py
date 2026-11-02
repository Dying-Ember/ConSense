import time
import unittest
from unittest import mock

from fixed_gpu_client import BusinessStream, Client, OperationError


class CleanupTest(unittest.TestCase):
    def test_watcher_start_failure_still_closes_connection(self):
        client = Client('http://127.0.0.1:1234','fixture',{'business-llm':'e'*64})
        connection, watcher = mock.Mock(), mock.Mock()
        watcher.start.side_effect = RuntimeError('watcher-start')
        with mock.patch.object(client,'_connection',return_value=connection), \
             mock.patch('fixed_gpu_client.threading.Thread',return_value=watcher):
            with self.assertRaisesRegex(RuntimeError,'watcher-start'):
                client._call('GET','/fixture',1,cancelled=lambda: False)
        connection.close.assert_called_once()
        watcher.join.assert_not_called()

    def test_stream_header_failure_still_cancels_and_preserves_error(self):
        client = mock.Mock()
        client.cancel_and_observe.return_value = {'status':'release_unknown','operationId':'a'*32}
        stream = BusinessStream(client,'a'*32,time.monotonic()+2,{},None)
        stream.response = mock.Mock(status=200)
        stream.response.getheader.side_effect = ValueError('header-error')
        stream.response.close.side_effect = OSError('response-close')
        stream.connection = mock.Mock()
        with self.assertRaisesRegex(ValueError,'header-error') as caught: next(stream.read())
        self.assertEqual(str(caught.exception.__cause__),'response-close')
        client.cancel_and_observe.assert_called_once()
        self.assertEqual('release_unknown',stream.exit_receipt['status'])

    def test_caller_handling_unrelated_exception_does_not_replace_cleanup_error(self):
        client = Client('http://127.0.0.1:1234','fixture',{'business-llm':'e'*64})
        response = mock.Mock(status=200, length=0)
        response.getheader.return_value = '2'; response.isclosed.side_effect = [False, True]
        response.read1.return_value = b'{}'; response.close.side_effect = OSError('response-close')
        connection = mock.Mock(sock=mock.Mock()); connection.getresponse.return_value = response
        try: raise ValueError('unrelated-caller-error')
        except ValueError:
            with mock.patch.object(client,'_connection',return_value=connection):
                with self.assertRaisesRegex(OSError,'response-close'): client._call('GET','/fixture',1)
        connection.close.assert_called_once()

    def test_business_open_failure_survives_cleanup_failure(self):
        client = Client('http://127.0.0.1:1234','fixture',{'business-llm':'e'*64})
        state = {'operationId':'a'*32,'stage':'business-llm','bindingSha256':'b'*64}
        client._call = mock.Mock(return_value=state)
        client.cancel_and_observe = mock.Mock(return_value={'status':'release_unknown','operationId':'a'*32})
        connection = mock.Mock(sock=mock.Mock())
        connection.getresponse.side_effect = ValueError('open-error')
        connection.close.side_effect = OSError('connection-close')
        stream = BusinessStream(client,'a'*32,time.monotonic()+2,{},None)
        with mock.patch.object(client,'_connection',return_value=connection):
            with self.assertRaisesRegex(ValueError,'open-error') as caught: stream.open()
        self.assertEqual(str(caught.exception.__cause__),'connection-close')
        client.cancel_and_observe.assert_called_once()
        self.assertEqual('release_unknown',stream.exit_receipt['status'])

    def test_business_read_failure_survives_cleanup_failure(self):
        client = mock.Mock()
        client.cancel_and_observe.return_value = {'status':'release_unknown','operationId':'a'*32}
        stream = BusinessStream(client,'a'*32,time.monotonic()+2,{},None)
        stream.response = mock.Mock(status=200)
        stream.response.getheader.return_value = 'text/event-stream'
        stream.response.isclosed.return_value = False
        stream.response.read1.side_effect = ValueError('read-error')
        stream.response.close.side_effect = OSError('response-close')
        stream.connection = mock.Mock()
        with self.assertRaisesRegex(ValueError,'read-error') as caught: next(stream.read())
        self.assertEqual(str(caught.exception.__cause__),'response-close')
        client.cancel_and_observe.assert_called_once()
        self.assertEqual('release_unknown',stream.exit_receipt['status'])

    def test_complete_admission_cleanup_failure_cancels_known_id_for_all_callers(self):
        import test_admission_race_candidate as fixtures
        from fixed_model_session import ModelSession
        raw = b'{"operationId":"'+fixtures.OPERATION.encode()+b'"}'
        for kind in ('execute','business','session'):
            for source in ('response','connection'):
                with self.subTest(kind=kind, source=source):
                    client = Client('http://127.0.0.1:1234','fixture',
                                    {'business-llm':'e'*64,'document-embedding':'c'*64})
                    response = mock.Mock(status=202, length=0)
                    response.getheader.return_value = str(len(raw)); response.isclosed.side_effect = [False, True]
                    response.read1.return_value = raw
                    connection = mock.Mock(sock=mock.Mock()); connection.getresponse.return_value = response
                    (response if source == 'response' else connection).close.side_effect = OSError('close-error')
                    client._connection = lambda timeout: connection
                    client.cancel_and_observe = mock.Mock(return_value={'status':'release_unknown','operationId':fixtures.OPERATION})
                    with self.assertRaises(OperationError) as caught:
                        if kind == 'execute': client.compute('document-embedding',{'x':1},{'x':1},2)
                        elif kind == 'business': client.start_business('/v1/responses',b'{}',2)
                        else: ModelSession.start(client,'document-embedding',fixtures.BINDING,time.monotonic()+2)
                    self.assertEqual(fixtures.OPERATION,caught.exception.operation)
                    self.assertEqual('release_unknown',caught.exception.exit_receipt['status'])
                    self.assertEqual('close-error',str(caught.exception.__cause__.__cause__))
                    client.cancel_and_observe.assert_called_once()

    def test_original_read_error_survives_close_error(self):
        client = Client('http://127.0.0.1:1234','fixture',{'business-llm':'e'*64})
        response = mock.Mock(status=200, length=2)
        response.getheader.return_value = '2'; response.isclosed.return_value = False
        response.read1.side_effect = ValueError('read-error')
        response.close.side_effect = OSError('response-close')
        connection = mock.Mock(sock=mock.Mock()); connection.getresponse.return_value = response
        with mock.patch.object(client,'_connection',return_value=connection):
            with self.assertRaisesRegex(ValueError,'read-error') as caught: client._call('GET','/fixture',1)
        self.assertEqual(str(caught.exception.__cause__),'response-close')
        connection.close.assert_called_once()

    def test_cancelled_complete_admission_keeps_id_when_close_fails(self):
        import test_admission_race_candidate as fixtures
        client, cancelled = fixtures.AdmissionRaceTest().make_client(
            b'{"operationId":"'+fixtures.OPERATION.encode()+b'"}')
        response = client._connection(1).getresponse.return_value
        response.close.side_effect = OSError('response-close')
        with self.assertRaises(OperationError) as caught:
            client.start_business('/v1/responses',b'{}',2,cancelled.is_set)
        self.assertEqual(fixtures.OPERATION,caught.exception.operation)
        self.assertEqual('release_unknown',caught.exception.exit_receipt['status'])
        self.assertEqual(str(caught.exception.__cause__.__cause__),'response-close')
        client.cancel_and_observe.assert_called_once()

    def test_call_closes_connection_even_if_response_close_fails(self):
        for both in (False, True):
            with self.subTest(connection_fails=both):
                client = Client('http://127.0.0.1:1234','fixture',{'business-llm':'e'*64})
                response = mock.Mock(status=200, length=0)
                response.getheader.return_value = '2'
                response.isclosed.side_effect = [False, True]
                response.read1.return_value = b'{}'
                response.close.side_effect = OSError('response-close')
                connection = mock.Mock(sock=mock.Mock())
                connection.getresponse.return_value = response
                if both: connection.close.side_effect = OSError('connection-close')
                with mock.patch.object(client, '_connection', return_value=connection):
                    with self.assertRaises(OSError) as caught: client._call('GET','/fixture',1)
                connection.close.assert_called_once()
                self.assertEqual(str(caught.exception), 'connection-close' if both else 'response-close')
                if both: self.assertEqual(str(caught.exception.__context__), 'response-close')

    def test_stream_closes_both_and_cancels_despite_close_errors(self):
        for response_error, connection_error in ((True, False), (False, True), (True, True)):
            with self.subTest(response_error=response_error, connection_error=connection_error):
                client = mock.Mock()
                client.cancel_and_observe.return_value = {'status':'release_unknown','operationId':'a'*32}
                stream = BusinessStream(client,'a'*32,time.monotonic()+2,{},None)
                stream.response, stream.connection = mock.Mock(), mock.Mock()
                response, connection = stream.response, stream.connection
                if response_error: response.close.side_effect = OSError('response-close')
                if connection_error: connection.close.side_effect = OSError('connection-close')
                with self.assertRaises(OSError) as caught: stream.close()
                response.close.assert_called_once(); connection.close.assert_called_once()
                client.cancel_and_observe.assert_called_once_with('a'*32,{})
                self.assertEqual('release_unknown', stream.exit_receipt['status'])
                self.assertEqual(str(caught.exception), 'connection-close' if connection_error else 'response-close')
                if response_error and connection_error:
                    self.assertEqual(str(caught.exception.__context__), 'response-close')

    def test_cancel_observation_failure_keeps_unknown_and_prior_close_context(self):
        client = mock.Mock()
        client.cancel_and_observe.side_effect = OSError('observation-failed')
        stream = BusinessStream(client,'a'*32,time.monotonic()+2,{},None)
        stream.response, stream.connection = mock.Mock(), mock.Mock()
        stream.response.close.side_effect = OSError('response-close')
        connection = stream.connection
        with self.assertRaisesRegex(OSError,'observation-failed') as caught: stream.close()
        self.assertEqual(str(caught.exception.__context__),'response-close')
        self.assertEqual({'status':'release_unknown','operationId':'a'*32},stream.exit_receipt)
        connection.close.assert_called_once()
        client.cancel_and_observe.assert_called_once()


if __name__ == '__main__': unittest.main()
