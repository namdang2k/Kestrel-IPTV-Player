using NUnit.Framework;
using StbEmu.Services;

namespace StbEmu.Tests
{
    public class RatePromptTests
    {
        static RatePromptState At(double appSeconds) => new RatePromptState { appSeconds = appSeconds };

        [Test]
        public void FirstPromptNeedsTwentyMinutesOfAppTimeAndFiveMinutesOfSession()
        {
            Assert.IsFalse(RatePromptPolicy.IsDue(At(1199), 400));
            Assert.IsFalse(RatePromptPolicy.IsDue(At(1200), 299));
            Assert.IsTrue(RatePromptPolicy.IsDue(At(1200), 300));
        }

        [Test]
        public void NotNowWaitsAnotherTwentyMinutesEachTime()
        {
            var state = At(1200);
            RatePromptPolicy.NotNow(state);
            Assert.IsFalse(RatePromptPolicy.IsDue(state, 600));
            state.appSeconds = 2400;
            Assert.IsTrue(RatePromptPolicy.IsDue(state, 600));
            RatePromptPolicy.NotNow(state);
            state.appSeconds = 3599;
            Assert.IsFalse(RatePromptPolicy.IsDue(state, 600));
            state.appSeconds = 3600;
            Assert.IsTrue(RatePromptPolicy.IsDue(state, 600));
        }

        [Test]
        public void NotNowIsCountedFromWhenItWasTappedNotFromTheOldThreshold()
        {
            var state = At(2000); // prompt was held back until now
            RatePromptPolicy.NotNow(state);
            Assert.AreEqual(3200, state.nextAt);
        }

        [Test]
        public void StoppedNeverShowsAgain()
        {
            var state = At(1e7);
            state.stop = true;
            Assert.IsFalse(RatePromptPolicy.IsDue(state, 1e7));
        }
    }
}
