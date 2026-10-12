using Newtonsoft.Json.Linq;
using NUnit.Framework;
using StbEmu.Profiles;
using StbEmu.UI;

namespace StbEmu.Tests
{
    public class ProfileFormTests
    {
        static Profile Stored() => new Profile
        {
            ProfileId = "p1",
            Name = "Nhà \"A\"",
            ServerUrl = "http://h:8080",
            Username = "bob",
            Password = "secret",
            Compatibility = { UserAgentOverride = "UA", PlayerUserAgentOverride = "PUA" },
            Playback = { MinBufferMs = 5000, Tunneling = true },
        };

        [Test]
        public void Apply_WritesFormFields_KeepsTheRest()
        {
            var profile = Stored();
            ProfileForm.Apply(profile, new JObject
            {
                ["name"] = "  Đổi tên ", ["serverUrl"] = " x.test:8080 ", ["username"] = " alice ", ["password"] = " p w ",
                ["userAgent"] = " UA2 ", ["playerUserAgent"] = "",
            });
            Assert.AreEqual("Đổi tên", profile.Name);
            Assert.AreEqual("x.test:8080", profile.ServerUrl); // normalization is the manager's job
            Assert.AreEqual("alice", profile.Username);
            Assert.AreEqual(" p w ", profile.Password);
            Assert.AreEqual("UA2", profile.Compatibility.UserAgentOverride);
            Assert.AreEqual("", profile.Compatibility.PlayerUserAgentOverride);
            Assert.AreEqual("p1", profile.ProfileId);
            Assert.AreEqual(5000, profile.Playback.MinBufferMs);
            Assert.IsTrue(profile.Playback.Tunneling);
        }

        [Test]
        public void Apply_MissingOrMistypedKeys_DoNotOverwrite()
        {
            var profile = Stored();
            ProfileForm.Apply(profile, new JObject { ["name"] = 5, ["password"] = true });
            Assert.AreEqual("Nhà \"A\"", profile.Name);
            Assert.AreEqual("secret", profile.Password);
        }

        [Test]
        public void ToForm_Apply_RoundTrips()
        {
            var source = Stored();
            var copy = ProfileForm.Copy(source);
            ProfileForm.Apply(copy, JObject.Parse(ProfileForm.ToForm(source, "Edit profile").ToString()));
            Assert.AreEqual(source.ServerUrl, copy.ServerUrl);
            Assert.AreEqual(source.Username, copy.Username);
            Assert.AreEqual(source.Password, copy.Password);
            Assert.AreEqual(source.Compatibility.UserAgentOverride, copy.Compatibility.UserAgentOverride);
            Assert.AreEqual(source.Compatibility.PlayerUserAgentOverride, copy.Compatibility.PlayerUserAgentOverride);
        }

        [Test]
        public void Copy_IsDeep_AndNullIsNewProfile()
        {
            var source = Stored();
            var copy = ProfileForm.Copy(source);
            copy.Username = "changed";
            Assert.AreEqual("bob", source.Username);
            var fresh = ProfileForm.Copy(null);
            Assert.AreEqual("", fresh.ProfileId);
            Assert.AreEqual("", fresh.Password);
        }
    }
}
