package io.panelassistant.android.dashboard

import java.io.File
import io.panelassistant.android.logship.CdpConsoleMapper
import io.panelassistant.android.logship.LogCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRejectionLoggingTest {
    @Test fun rejectionConsoleRecordsKeepReasonStackAndDefaultHandling() {
        val harness = File.createTempFile("dashboard-rejection", ".js")
        try {
            harness.writeText("""
                const assert=require('node:assert/strict');
                const window=new EventTarget();window.top=window;
                const records=[];const console={error:message=>records.push(message)};
                ${InjectionScript.rejectionLoggingJs()}
                function reject(reason){
                    const event=new Event('unhandledrejection',{cancelable:true});event.reason=reason;
                    assert.equal(window.dispatchEvent(event),true);
                    assert.equal(event.defaultPrevented,false);
                    return records.pop();
                }
                const error=new Error('card rendering failed');
                const errorRecord=reject(error);
                assert.ok(errorRecord.includes('card rendering failed'));
                assert.ok(errorRecord.includes(error.stack));
                assert.ok(!errorRecord.includes('Handler observation stack'));
                const objectRecord=reject({code:'connection_lost',message:'socket closed'});
                assert.ok(objectRecord.includes('"code":"connection_lost"'));
                assert.ok(objectRecord.includes('"message":"socket closed"'));
                assert.ok(objectRecord.includes('Handler observation stack (rejection origin unavailable):\nError'));
                const longError=new Error('long reason '+ 'x'.repeat(6000));longError.stack='Error: long reason\n at failingCard (card.js:42:7)';
                const longRecord=reject(longError);
                assert.ok(longRecord.includes('at failingCard (card.js:42:7)'));
                assert.ok(longRecord.length<4096);
                const circular={message:'circular card failure',code:'card_error'};circular.self=circular;
                const circularRecord=reject(circular);
                assert.ok(circularRecord.includes('circular card failure'));
                assert.ok(circularRecord.includes('card_error'));
                const hostile={message:'serializer failed',toJSON(){throw new Error('no JSON')},toString(){throw new Error('no string')}};
                assert.ok(reject(hostile).includes('serializer failed'));
                for(const reason of ['plain reason',null,undefined,17])assert.ok(reject(reason).includes(String(reason)));
                window.top={};
                ${InjectionScript.rejectionLoggingJs()}
                reject('one listener');assert.equal(records.length,0);
                process.stdout.write(JSON.stringify({method:'Runtime.consoleAPICalled',params:{type:'error',timestamp:1,args:[{type:'string',value:longRecord+'\n'+reject({access_token:'sensitive-token-value',message:'request rejected'})}]}}));
            """.trimIndent())
            val process = ProcessBuilder("node", harness.absolutePath).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
            val event = requireNotNull(CdpConsoleMapper.map(output))
            val captured = CdpConsoleMapper.format(event.level, event.text, event.timestampMs)
            assertTrue(captured.contains("E webview/console: Unhandled promise rejection: Error: long reason"))
            assertTrue(captured.contains("at failingCard (card.js:42:7)"))
            assertTrue(captured.contains("sensitive-token-value"))
            assertTrue(!LogCapture.redact(captured).contains("sensitive-token-value"))
        } finally {
            harness.delete()
        }
    }
}
