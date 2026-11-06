// Real dispatch and handler with bounded external-command capture replaced by raw fixture bytes.
// Also links against the pre-feature production sources: positive replies fail, controls stay green.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include "dispatch.h"
static int failures, capture_status, captures;
static const void *capture_bytes;
static size_t capture_size;
#define CHECK(c, text) do { if (!(c)) { printf("FAIL: %s (%s:%d)\n", text, __FILE__, __LINE__); failures++; } } while (0)
int __wrap_sysexec_capture_wifi_status(char *output, size_t capacity, size_t *used) {
    captures++;
    size_t n = capture_size < capacity ? capture_size : capacity - 1;
    memcpy(output, capture_bytes, n); output[n] = 0; *used = n;
    return capture_status;
}
static void command(const char *line, char *out, size_t capacity) {
    int pair[2];CHECK(socketpair(AF_UNIX,SOCK_STREAM,0,pair)==0,"WiFi dispatch fixture socket opens");
    conn_ctx ctx = {0}; ctx.fd=pair[0]; char input[256];snprintf(input,sizeof input,"%s",line);
    dispatch(&ctx,input);shutdown(pair[0],SHUT_WR);
    size_t n=0;ssize_t got;
    while(n+1<capacity && (got=read(pair[1],out+n,capacity-1-n))>0)n+=(size_t)got;
    out[n]=0;close(pair[0]);close(pair[1]);
}
static void sample(const char *raw,const char *expected,const char *assertion) {
    capture_bytes=raw;capture_size=strlen(raw);capture_status=0;
    char out[128];command("WIFISTATUS",out,sizeof out);CHECK(!strcmp(out,expected),assertion);
}
int main(void) {
    char out[128];capture_bytes="";
    command("PING",out,sizeof out);CHECK(!strcmp(out,"OK\n"),"WiFi pre-feature PING control stays green");
    const char *bad[]={"WIFISTATUS extra","WIFISTATUS /proc","WIFISTATUS ;reboot","WIFISTATUS\nPING"};
    for(size_t i=0;i<sizeof bad/sizeof bad[0];i++){int before=captures;command(bad[i],out,sizeof out);CHECK(!strcmp(out,"ERR\n") && captures==before,"WiFi rejects every argument before capture");}
    sample("Wifi is connected to \"Home, β\"\nWifiInfo: SSID: \"Home, β\", BSSID: 00:11:22:33:44:55, MAC: aa:bb:cc:dd:ee:ff, RSSI: -63, Link speed: 433Mbps\n", "SSID=486f6d652c20ceb2 RSSI=-63\n", "WiFi current record supports quoted comma and UTF8 without private fields");
    sample("mWifiInfo SSID: Home, BSSID: 00:11:22:33:44:55, RSSI: -126, Link speed: 72Mbps\n", "SSID=486f6d65 RSSI=-126\n", "WiFi legacy current record supports bare SSID and lower RSSI bound");
    sample("mWifiInfo=SSID: Home, BSSID: aa, RSSI: 0\n", "SSID=486f6d65 RSSI=0\n", "WiFi zero RSSI bound is canonical");
    sample("historical RSSI: -1\nscan SSID: Wrong, BSSID: aa, RSSI: -2\nWifi is connected to \"Wrong\"\nWifiInfo: SSID: Right, BSSID: bb, RSSI: -66\n", "SSID=5269676874 RSSI=-66\n", "WiFi fields come only from the same current record");
    sample("WifiInfo: SSID: <unknown ssid>, BSSID: aa, RSSI: -70\n", "SSID=- RSSI=-70\n", "WiFi unknown SSID preserves independently valid RSSI");
    sample("WifiInfo: SSID: Home, BSSID: aa, RSSI: -127\n", "SSID=486f6d65 RSSI=-\n", "WiFi invalid RSSI preserves independently valid SSID");
    const char *signals[]={"1","-127","-0","+0","-01","-63junk","-99999999999999999999999"};
    for(size_t i=0;i<sizeof signals/sizeof signals[0];i++){char raw[160];snprintf(raw,sizeof raw,"WifiInfo: SSID: Home, BSSID: aa, RSSI: %s\n",signals[i]);sample(raw,"SSID=486f6d65 RSSI=-\n","WiFi rejects malformed or out-of-range RSSI");}
    sample("WifiInfo: SSID: First, BSSID: aa, RSSI: -1\nWifiInfo: SSID: Second, BSSID: bb, RSSI: -2\n", "SSID=- RSSI=-\n", "WiFi multiple current records are unavailable");
    sample("WifiInfo: SSID: Home, BSSID: aa, RSSI: -1, RSSI: -2\n", "SSID=- RSSI=-\n", "WiFi duplicate current fields are ambiguous");
    sample("WifiInfo: SSID: First, BSSID: aa, SSID: Second, RSSI: -70\n", "SSID=- RSSI=-\n", "WiFi duplicate SSID fields are ambiguous");
    sample("WifiInfo: SSID: \"bad\"quote\", BSSID: aa, RSSI: -70\n", "SSID=- RSSI=-70\n", "WiFi ambiguous SSID quoting cannot become a name");
    sample("WifiInfo: SSID: \"fake, BSSID: xx\", BSSID: aa, RSSI: -70\n", "SSID=- RSSI=-\n", "WiFi ambiguous field delimiter never supplies measurements");
    sample("history RSSI: -30\nWifi is connected to \"BannerOnly\"\n", "SSID=- RSSI=-\n", "WiFi unrecognized output cannot borrow banner or history");
    sample("WifiInfo: SSID: 0x486f6d65, BSSID: aa, RSSI: -70\n", "SSID=- RSSI=-70\n", "WiFi unsupported hex SSID preserves RSSI");
    const char *invalid[]={"WifiInfo: SSID: bad\001name, BSSID: aa, RSSI: -70\n","WifiInfo: SSID: bad\302\205name, BSSID: aa, RSSI: -70\n","WifiInfo: SSID: bad\300\257name, BSSID: aa, RSSI: -70\n"};
    for(size_t i=0;i<sizeof invalid/sizeof invalid[0];i++)sample(invalid[i],"SSID=- RSSI=-70\n","WiFi malformed or control SSID preserves RSSI");
    sample("WifiInfo: SSID: abcdefghijklmnopqrstuvwxyz123456, BSSID: aa, RSSI: -70\n", "SSID=6162636465666768696a6b6c6d6e6f707172737475767778797a313233343536 RSSI=-70\n", "WiFi accepts exactly 32 SSID bytes");
    sample("WifiInfo: SSID: abcdefghijklmnopqrstuvwxyz1234567, BSSID: aa, RSSI: -70\n", "SSID=- RSSI=-70\n", "WiFi rejects SSID byte overflow");
    sample("Error: service denied\nWifiInfo: SSID: Stale, BSSID: aa, RSSI: -70\n", "ERR\n", "WiFi selected capture failure prefix cannot expose stale fields");
    const char nul[]={"WifiInfo: SSID: Stale, BSSID: aa, RSSI: -70\n\0hidden"};
    capture_bytes=nul;capture_size=sizeof nul-1;command("WIFISTATUS",out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"WiFi embedded NUL invalidates a valid prefix");
    capture_bytes="";capture_size=0;command("WIFISTATUS",out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"WiFi empty successful capture is unavailable");
    for(int status=-2;status<=1;status++){if(!status)continue;capture_bytes="WifiInfo: SSID: Stale, BSSID: aa, RSSI: -70\n";capture_size=strlen(capture_bytes);capture_status=status;command("WIFISTATUS",out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"WiFi capture failure discards valid partial fields");}
    if(failures)return 1;
    puts("WIFI BOUNDARY OK");return 0;
}
