using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using Cysharp.Threading.Tasks;
using NUnit.Framework;
using StbEmu.Profiles;
using StbEmu.Storage;

namespace StbEmu.Tests
{
    public class ProfileTests
    {
        string dir;

        [SetUp]
        public void SetUp()
        {
            dir = Path.Combine(Path.GetTempPath(), "stbemu-tests-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(dir);
        }

        [TearDown]
        public void TearDown() => Directory.Delete(dir, true);

        static Profile NewProfile(string mac = "") => new Profile
        {
            Name = "Test portal",
            PortalUrl = "https://portal.example.test/c/",
            Identity = { Mac = mac },
        };

        [TestCase("001a79123456", "00:1A:79:12:34:56")]
        [TestCase("00-1a-79-12-34-56", "00:1A:79:12:34:56")]
        [TestCase(" 00:1A:79:AB:CD:EF ", "00:1A:79:AB:CD:EF")]
        [TestCase("00:1A:79:12:34", null)]
        [TestCase("00:1A:79:12:34:5G", null)]
        [TestCase("", null)]
        public void MacNormalize(string input, string expected) => Assert.AreEqual(expected, MacAddress.Normalize(input));

        [Test]
        public void MacGenerate_IsCanonicalWithPrefix()
        {
            var mac = MacAddress.Generate();
            StringAssert.StartsWith("00:1A:79:", mac);
            Assert.AreEqual(mac, MacAddress.Normalize(mac));
        }

        [TestCase("00:1A:79:12:34:56")]
        [TestCase("001a79123456")]
        public void DeviceIdFromMac_MatchesStbEmu(string mac) =>
            Assert.AreEqual("667094E7E8FF0347F6EC27A8E8115BE76E2AA9CD5C8F13C6FA00BCEDFAC02B41", MacAddress.ToDeviceId(mac));

        [Test]
        public async Task Add_GeneratesMac_AndSurvivesReload()
        {
            var manager = new ProfileManager(new JsonProfileRepository(dir));
            var added = await manager.AddAsync(NewProfile(), CancellationToken.None);

            var reloaded = new ProfileManager(new JsonProfileRepository(dir));
            await reloaded.LoadAsync(CancellationToken.None);

            Assert.AreEqual(1, reloaded.Profiles.Count);
            Assert.AreEqual(added.Identity.Mac, reloaded.Profiles[0].Identity.Mac);
            Assert.AreEqual("https://portal.example.test/c/", reloaded.Profiles[0].PortalUrl);
        }

        [Test]
        public async Task Add_NormalizesEnteredMac_AndRejectsDuplicate()
        {
            var manager = new ProfileManager(new JsonProfileRepository(dir));
            var first = await manager.AddAsync(NewProfile("001a79123456"), CancellationToken.None);
            Assert.AreEqual("00:1A:79:12:34:56", first.Identity.Mac);

            var ex = await Throws<ProfileValidationException>(manager.AddAsync(NewProfile("00:1A:79:12:34:56"), CancellationToken.None).AsUniTask());
            StringAssert.Contains("already used", ex.Message);
            Assert.AreEqual(1, manager.Profiles.Count);
        }

        [Test]
        public async Task Add_RejectsInvalidUrl()
        {
            var manager = new ProfileManager(new JsonProfileRepository(dir));
            var profile = NewProfile();
            profile.PortalUrl = "portal.example.test";
            await Throws<ProfileValidationException>(manager.AddAsync(profile, CancellationToken.None).AsUniTask());
        }

        [Test]
        public async Task Load_CorruptMainFile_RestoresFromBackup()
        {
            var manager = new ProfileManager(new JsonProfileRepository(dir));
            await manager.AddAsync(NewProfile(), CancellationToken.None); // creates profiles.json
            await manager.AddAsync(NewProfile(), CancellationToken.None); // previous file -> profiles.json.bak (1 profile)

            File.WriteAllText(Path.Combine(dir, "profiles.json"), "{ truncated");
            UnityEngine.TestTools.LogAssert.Expect(UnityEngine.LogType.Warning, new System.Text.RegularExpressions.Regex("restored from backup"));

            var reloaded = new ProfileManager(new JsonProfileRepository(dir));
            await reloaded.LoadAsync(CancellationToken.None);
            Assert.AreEqual(1, reloaded.Profiles.Count);
        }

        [Test]
        public async Task Load_NewerSchema_Throws()
        {
            File.WriteAllText(Path.Combine(dir, "profiles.json"), "{\"schemaVersion\": 99, \"profiles\": []}");
            var manager = new ProfileManager(new JsonProfileRepository(dir));
            await Throws<ProfileStoreException>(manager.LoadAsync(CancellationToken.None));
        }

        [Test]
        public async Task Add_SaveFails_RollsBack()
        {
            var manager = new ProfileManager(new FailingRepository());
            await Throws<IOException>(manager.AddAsync(NewProfile(), CancellationToken.None).AsUniTask());
            Assert.AreEqual(0, manager.Profiles.Count);
        }

        [Test]
        public async Task Load_TypeNameInJson_IsIgnored()
        {
            // Imported JSON must not instantiate arbitrary .NET types.
            File.WriteAllText(Path.Combine(dir, "profiles.json"),
                "{\"$type\":\"System.IO.FileInfo, mscorlib\",\"schemaVersion\":1,\"profiles\":[]}");
            var manager = new ProfileManager(new JsonProfileRepository(dir));
            await manager.LoadAsync(CancellationToken.None);
        }

        // Assert.ThrowsAsync blocks the main thread and deadlocks on Unity's SynchronizationContext; await instead.
        static async Task<T> Throws<T>(UniTask task) where T : Exception
        {
            try { await task; }
            catch (T e) { return e; }
            Assert.Fail($"Expected {typeof(T).Name}");
            return null;
        }

        sealed class FailingRepository : IProfileRepository
        {
            public UniTask<ProfileDocument> LoadAsync(CancellationToken ct) => UniTask.FromResult(new ProfileDocument());
            public UniTask SaveAsync(ProfileDocument document, CancellationToken ct) => throw new IOException("disk full");
        }
    }
}
