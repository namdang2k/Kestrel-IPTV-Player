using System;
using System.Collections.Generic;

namespace StbEmu.Profiles
{
    public static class ProfileValidator
    {
        /// <summary>Returns human-readable errors; empty list means valid. <paramref name="others"/> excludes the profile itself.</summary>
        public static List<string> Validate(Profile profile, IEnumerable<Profile> others)
        {
            var errors = new List<string>();

            if (string.IsNullOrWhiteSpace(profile.Name))
                errors.Add("Name is required.");

            if (!Uri.TryCreate(profile.PortalUrl, UriKind.Absolute, out var uri)
                || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps)
                || string.IsNullOrEmpty(uri.Host))
                errors.Add("Portal URL must be a full http(s) URL, e.g. https://portal.example.test/c/.");

            var mac = profile.Identity?.Mac;
            if (MacAddress.Normalize(mac) != mac)
                errors.Add("MAC must be in the form 00:1A:79:12:34:56.");

            foreach (var other in others)
                if (other.Identity?.Mac == mac)
                    errors.Add($"MAC {mac} is already used by profile \"{other.Name}\".");

            return errors;
        }
    }
}
