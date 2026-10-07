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
            PortalUrl = "https://portal.example.test/c/",
            Identity = { Mac = "00:1A:79:12:34:56", DeviceId = "D1", SendDeviceId = true },
            Compatibility = { PortalLanguage = "vi", Timezone = "Asia/Ho_Chi_Minh" },
            Playback = { MinBufferMs = 5000, Tunneling = true },
            ForceLoginPortal = false,
        };

        [Test]
        public void Apply_WritesFormFields_KeepsTheRest()
        {
            var profile = Stored();
            ProfileForm.Apply(profile, new JObject
            {
                ["name"] = "  Đổi tên ", ["portalUrl"] = "http://x.test/c/", ["mac"] = "001a79aabbcc",
                ["sendDeviceId"] = false, ["forceLoginPortal"] = true,
            });
            Assert.AreEqual("Đổi tên", profile.Name);
            Assert.AreEqual("http://x.test/c/", profile.PortalUrl);
            Assert.AreEqual("001a79aabbcc", profile.Identity.Mac); // normalization is the manager's job
            Assert.IsFalse(profile.Identity.SendDeviceId);
            Assert.IsTrue(profile.ForceLoginPortal);
            Assert.AreEqual("p1", profile.ProfileId);
            Assert.AreEqual("D1", profile.Identity.DeviceId);
            Assert.AreEqual("vi", profile.Compatibility.PortalLanguage);
            Assert.AreEqual(5000, profile.Playback.MinBufferMs);
            Assert.IsTrue(profile.Playback.Tunneling);
        }

        [Test]
        public void Apply_MissingOrMistypedKeys_DoNotOverwrite()
        {
            var profile = Stored();
            ProfileForm.Apply(profile, new JObject { ["name"] = 5, ["sendDeviceId"] = "yes" });
            Assert.AreEqual("Nhà \"A\"", profile.Name);
            Assert.IsTrue(profile.Identity.SendDeviceId);
        }

        [Test]
        public void ToForm_Apply_RoundTrips()
        {
            var source = Stored();
            var copy = ProfileForm.Copy(source);
            ProfileForm.Apply(copy, JObject.Parse(ProfileForm.ToForm(source, "Edit profile").ToString()));
            Assert.AreEqual(source.Name, copy.Name);
            Assert.AreEqual(source.Identity.Mac, copy.Identity.Mac);
            Assert.AreEqual(source.Identity.Model, copy.Identity.Model);
            Assert.AreEqual(source.ForceLoginPortal, copy.ForceLoginPortal);
        }

        [Test]
        public void Copy_IsDeep_AndNullIsNewProfile()
        {
            var source = Stored();
            var copy = ProfileForm.Copy(source);
            copy.Identity.Mac = "changed";
            Assert.AreEqual("00:1A:79:12:34:56", source.Identity.Mac);
            var fresh = ProfileForm.Copy(null);
            Assert.AreEqual("", fresh.ProfileId);
            Assert.AreEqual("MAG250", fresh.Identity.Model);
            Assert.IsTrue(fresh.ForceLoginPortal);
        }
    }
}
