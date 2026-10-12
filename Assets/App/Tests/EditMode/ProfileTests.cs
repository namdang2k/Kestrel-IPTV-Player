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

        static Profile NewProfile(string server = "http://xtream.example.test:8080", string user = "bob") => new Profile
        {
            Name = "Test server",
            ServerUrl = server,
            Username = user,
            Password = "secret",
        };

        [TestCase("  xtream.example.test:8080/ ", "http://xtream.example.test:8080")]
        [TestCase("http://h/player_api.php?username=a&password=b", "http://h")]
        [TestCase("https://h/xc/get.php?username=a&password=b&type=m3u", "https://h/xc")]
        [TestCase("http://h:80/PANEL_API.PHP", "http://h:80")]
        [TestCase("http://h:8080//", "http://h:8080")]
        [TestCase("", "")]
        [TestCase(null, "")]
        public void ServerAddress_Normalize(string input, string expected) => Assert.AreEqual(expected, ServerAddress.Normalize(input));

        [Test]
        public async Task Add_NormalizesUrl_AndSurvivesReload()
        {
            var manager = new ProfileManager(new JsonProfileRepository(dir));
            await manager.AddAsync(NewProfile("xtream.example.test:8080/"), CancellationToken.None);

            var reloaded = new ProfileManager(new JsonProfileRepository(dir));
            await reloaded.LoadAsync(CancellationToken.None);

            Assert.AreEqual(1, reloaded.Profiles.Count);
            Assert.AreEqual("http://xtream.example.test:8080", reloaded.Profiles[0].ServerUrl);
            Assert.AreEqual("bob", reloaded.Profiles[0].Username);
        }

        [Test]
        public async Task Add_KeepsPasswordExactly()
        {
            var manager = new ProfileManager(new JsonProfileRepository(dir));
            var profile = NewProfile();
            profile.Password = " p@ss/ w ";
            await manager.AddAsync(profile, CancellationToken.None);
            var reloaded = new ProfileManager(new JsonProfileRepository(dir));
            await reloaded.LoadAsync(CancellationToken.None);
            Assert.AreEqual(" p@ss/ w ", reloaded.Profiles[0].Password);
        }

        [Test]
        public async Task Add_RejectsSameAccountTypedDifferently()
        {
            var manager = new ProfileManager(new JsonProfileRepository(dir));
            await manager.AddAsync(NewProfile("http://xtream.example.test:8080"), CancellationToken.None);

            var ex = await Throws<ProfileValidationException>(
                manager.AddAsync(NewProfile("xtream.example.test:8080/"), CancellationToken.None).AsUniTask());
            StringAssert.Contains("already used", ex.Message);

            await manager.AddAsync(NewProfile("http://xtream.example.test:8080", "alice"), CancellationToken.None);
            Assert.AreEqual(2, manager.Profiles.Count);
        }

        [Test]
        public void Validate_RequiresUsernameAndPassword()
        {
            var errors = ProfileValidator.Validate(new Profile { Name = "n", ServerUrl = "http://h" }, Array.Empty<Profile>());
            Assert.That(errors, Has.Some.Contains("Username"));
            Assert.That(errors, Has.Some.Contains("Password"));
        }

        [Test]
        public async Task Load_OlderSchema_StartsEmpty()
        {
            File.WriteAllText(Path.Combine(dir, "profiles.json"),
                "{\"schemaVersion\":1,\"profiles\":[{\"name\":\"old\",\"portalUrl\":\"http://p/c/\"}]}");
            var manager = new ProfileManager(new JsonProfileRepository(dir));
            await manager.LoadAsync(CancellationToken.None);
            Assert.AreEqual(0, manager.Profiles.Count);
        }

        [Test]
        public async Task Add_RejectsInvalidUrl()
        {
            var manager = new ProfileManager(new JsonProfileRepository(dir));
            var profile = NewProfile();
            profile.ServerUrl = "ftp://example.test";
            await Throws<ProfileValidationException>(manager.AddAsync(profile, CancellationToken.None).AsUniTask());
        }

        [Test]
        public async Task Load_CorruptMainFile_RestoresFromBackup()
        {
            var manager = new ProfileManager(new JsonProfileRepository(dir));
            await manager.AddAsync(NewProfile(), CancellationToken.None); // creates profiles.json
            await manager.AddAsync(NewProfile(user: "alice"), CancellationToken.None); // previous file -> profiles.json.bak (1 profile)

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
                "{\"$type\":\"System.IO.FileInfo, mscorlib\",\"schemaVersion\":2,\"profiles\":[]}");
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
