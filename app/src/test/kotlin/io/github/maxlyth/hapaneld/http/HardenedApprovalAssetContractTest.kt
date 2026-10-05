package io.github.maxlyth.hapaneld.http

import io.panelassistant.android.BuildConfig
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HardenedApprovalAssetContractTest {
    // Source-text reason: loads the shipped openapi.json contract and runs shipped scripts in node.
    private val assetsDir: File by lazy {
        listOf(File("src/main/assets"), File("app/src/main/assets"), File("../app/src/main/assets"))
            .first(File::isDirectory)
    }

    private fun asset(name: String): String = File(assetsDir, name).readText()

    private fun nodeAvailable(): Boolean = runCatching {
        ProcessBuilder("node", "--version").start().waitFor() == 0
    }.getOrDefault(false)

    private fun runNode(script: String, vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf("node", "-e", script) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test fun openApiHardenedHttpOutcomeContract() {
        val document = JSONObject(asset("openapi.json"))
        assertEquals(BuildConfig.VERSION_NAME, document.getJSONObject("info").getString("version"))
        val guidance = document.getJSONObject("components").getJSONObject("responses")
            .getJSONObject("ApprovalRequired").getString("description").lowercase()
        assertTrue(guidance.contains("physically at the panel"))
        assertTrue(guidance.contains("panel's screen"))
        assertTrue(guidance.contains("cannot be approved remotely"))
        assertTrue(guidance.contains("retry the identical request from the same peer"))
        assertTrue(guidance.contains("one-use"))

        val paths = document.getJSONObject("paths")
        val protected = listOf(
            "POST /play",
            "POST /api/v1/play",
            "POST /api/v1/profiles/select",
            "POST /api/v1/profiles/activate",
            "POST /api/v1/profiles/rollback",
            "POST /api/v1/config",
            "GET /api/v1/config/export",
            "POST /api/v1/config/import",
            "POST /api/v1/restore",
            "POST /api/v1/config/revisions/{id}/restore",
            "POST /api/v1/install/component",
            "POST /api/v1/install/apk/commit",
            "POST /api/v1/install/apk/from-url",
            "POST /api/v1/guard-db/stage",
            "POST /api/v1/guard-db/discard",
            "POST /api/v1/guard-db/arm",
            "POST /api/v1/guard-db/bootstrap/export",
            "POST /api/v1/guard-db/arm/commit",
            "POST /api/v1/guard-db/refusal",
            "POST /api/v1/guard-db/cancel",
            "POST /api/v1/guard-db/action",
            "POST /api/v1/backup",
            "POST /api/v1/uninstall",
            "POST /api/v1/dashboard/clear-storage",
            "POST /api/v1/companion/repair-url",
            "POST /api/v1/action",
            "POST /api/v1/tame",
            "POST /api/v1/display/density",
            "POST /api/v1/power-safety/repair",
            "POST /api/v1/panel-assistant/transport/release",
        )
        protected.forEach { route ->
            val (method, path) = route.split(" ", limit = 2)
            val response = paths.getJSONObject(path).getJSONObject(method.lowercase())
                .getJSONObject("responses").getJSONObject("202")
            val documented = response.optString("\$ref").contains("ApprovalRequired") ||
                response.optString("description").contains("approval-required")
            assertTrue("$route must document its Hardened-mode 202 approval challenge", documented)
        }

        val input = paths.getJSONObject("/api/v1/input").getJSONObject("post").getJSONObject("responses")
        assertTrue(input.getJSONObject("403").getString("description").contains("remote-input-disabled"))
        assertFalse(input.getJSONObject("202").getString("description").contains("approval-required"))

        val configConflict = paths.getJSONObject("/api/v1/config").getJSONObject("post")
            .getJSONObject("responses").getJSONObject("409").getString("description")
        assertTrue(configConflict.contains("network ADB cannot be enabled"))
        assertTrue(configConflict.contains("saved separately"))

        val inspectResponses = paths.getJSONObject("/api/v1/inspect/start").getJSONObject("post")
            .getJSONObject("responses")
        assertFalse(inspectResponses.has("202"))
        assertTrue(inspectResponses.getJSONObject("409").getString("description")
            .contains("devtools-incompatible-with-hardened-mode"))
    }

    @Test fun backupApprovalJsonIsNeverSavedAsAnArchive() {
        assumeTrue("node not available", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            let blobReads=0,objectUrls=0,clicks=0;
            const ids={
              'bk-pw':{value:'secret'},'bk-plain':{checked:false},'bk-comp':{checked:true},
              'bk-msg':{textContent:''},'pswitch':{dataset:{selfName:'Test Panel'}}
            };
            global.window=global;
            global.location={href:'http://panel/install',hash:'',reload(){throw new Error('unexpected reload')}};
            global.document={
              body:{appendChild(){}},
              getElementById(id){return ids[id]||null},querySelector(){return null},querySelectorAll(){return []},
              addEventListener(){},
              createElement(tag){return {tagName:tag.toUpperCase(),remove(){},click(){clicks++}}}
            };
            global.fetch=(url)=>Promise.resolve(url==='api/v1/backup'?{
              status:202,ok:true,json:()=>Promise.resolve({ok:false,error:'approval-required',approval_id:'abc',message:'Approve this request on the panel, then retry it.'}),
              blob:()=>{blobReads++;return Promise.resolve(new Blob(['wrong']))}
            }:{status:200,ok:true,json:()=>Promise.resolve({present:false})});
            const NativeURL=global.URL;
            NativeURL.createObjectURL=()=>{objectUrls++;return 'blob:wrong'};
            NativeURL.revokeObjectURL=()=>{};
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            const button={disabled:false};
            global.doBackup(button);
            setImmediate(()=>setImmediate(()=>{
              if(blobReads||objectUrls||clicks)process.exit(2);
              if(ids['bk-msg'].textContent!=='Approve this request on the panel, then retry it.')process.exit(3);
              if(button.disabled)process.exit(4);
            }));
        """.trimIndent()
        val (code, output) = runNode(script, File(assetsDir, "install.js").absolutePath)
        assertEquals("backup approval response reached the archive download path:\n$output", 0, code)
    }

    @Test fun profileSelectionTreatsOnlyStructuredApproval202AsFailure() {
        assumeTrue("node not available", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm'),source=fs.readFileSync(process.argv[1],'utf8');
            const start=source.indexOf('function string('),end=source.indexOf('function yamlFetch(');
            if(start<0||end<=start)process.exit(2);
            vm.runInThisContext(source.slice(start,end));
            const response=(body)=>({status:202,ok:true,text:()=>Promise.resolve(JSON.stringify(body))});
            global.fetch=()=>Promise.resolve(response({status:'accepted',restart_required:true}));
            jsonFetch('/profiles/select').then(result=>{
              if(result.status!=='accepted')process.exit(3);
              global.fetch=()=>Promise.resolve(response({ok:false,error:'approval-required',message:'Approve this request on the panel, then retry it.'}));
              return jsonFetch('/profiles/select').then(()=>process.exit(4),error=>{
                if(error.status!==202||error.body.error!=='approval-required')process.exit(5);
                if(error.message!=='Approve this request on the panel, then retry it.')process.exit(6);
              });
            });
        """.trimIndent()
        val (code, output) = runNode(script, File(assetsDir, "profiles.js").absolutePath)
        assertEquals("profile selection confused staged and approval-required HTTP 202 responses:\n$output", 0, code)
    }
}
