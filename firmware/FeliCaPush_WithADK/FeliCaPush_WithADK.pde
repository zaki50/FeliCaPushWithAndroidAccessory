#include <RCS620S.h>
#include <inttypes.h>
#include <string.h>

#include <Max3421e.h>
#include <Usb.h>
#include <AndroidAccessory.h>

#define COMMAND_TIMEOUT  400
#define PUSH_TIMEOUT    2100
#define POLLING_INTERVAL 50

#define LED_PIN 13

RCS620S rcs620s;
int waitCardReleased = 0;

AndroidAccessory acc("Google, Inc.",
		     "RC620S",
		     "RC620S with Arduino Board",
		     "1.0",
		     "http://www.android.com",
		     "0000000012345678");

void setup()
{
  int ret;

  digitalWrite(LED_PIN, LOW);
  pinMode(LED_PIN, OUTPUT);

  Serial.begin(115200);

  ret = rcs620s.initDevice();
  while (!ret) {}
  
  // USB ホストコントローラ を起動
  acc.powerOn();
}

void loop()
{
  if (acc.isConnected()) {
    uint8_t msg[300];
    int len = acc.read(msg, sizeof(msg), 1);
    if (0 < len && msg[0] == (len - 1)) {
      do_push(msg + 1, len -1);
    }
  }
  
  // 常に実行する処理
  rcs620s.rfOff();
  digitalWrite(LED_PIN, LOW);
  delay(POLLING_INTERVAL);
}

void do_push(uint8_t data[], int length)
{
  int ret;

  // Polling
  rcs620s.timeout = COMMAND_TIMEOUT;
  ret = rcs620s.polling();
  if (!ret) {
    if (waitCardReleased) {
      waitCardReleased = 0;
    }
  } else if (!waitCardReleased) {
    // Push
  digitalWrite(LED_PIN, HIGH);
    rcs620s.timeout = PUSH_TIMEOUT;
    ret = rcs620s.push(data, length);
    if (ret) {
      waitCardReleased = 1;
    }
  }
}

