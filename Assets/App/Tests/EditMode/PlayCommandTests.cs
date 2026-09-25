using NUnit.Framework;
using StbEmu.Portal;

namespace StbEmu.Tests
{
    public class PlayCommandTests
    {
        [TestCase("ffmpeg http://x/live.m3u8", "http://x/live.m3u8")]
        [TestCase("ffrt http://x/1.ts", "http://x/1.ts")]
        [TestCase("ffrt3 https://x/1.ts", "https://x/1.ts")]
        [TestCase("auto   rtsp://x/cam", "rtsp://x/cam")]
        [TestCase("http://x/bare.m3u8", "http://x/bare.m3u8")]
        [TestCase("  ffmpeg  udp://239.0.0.1:1234  ", "udp://239.0.0.1:1234")]
        [TestCase("ffmpeg http://x/a b", "http://x/a b")] // portal-built URLs are not re-encoded
        public void Parses(string command, string expected)
        {
            Assert.IsTrue(PlayCommand.TryParseUrl(command, out var url));
            Assert.AreEqual(expected, url);
        }

        [TestCase("")]
        [TestCase("   ")]
        [TestCase(null)]
        [TestCase("ffmpeg")]
        [TestCase("ffmpeg /media/usb/a.mp4")]
        [TestCase("ffmpeg javascript:alert(1)")]
        [TestCase("ffmpeg file:///sdcard/a.mp4")]
        public void Rejects(string command)
        {
            Assert.IsFalse(PlayCommand.TryParseUrl(command, out var url));
            Assert.IsNull(url);
        }
    }
}
