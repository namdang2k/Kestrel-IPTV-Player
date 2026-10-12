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

            if (!Uri.TryCreate(profile.ServerUrl, UriKind.Absolute, out var uri)
                || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps)
                || string.IsNullOrEmpty(uri.Host))
                errors.Add("Server URL must be a full http(s) URL, e.g. http://example.test:8080.");

            if (string.IsNullOrWhiteSpace(profile.Username))
                errors.Add("Username is required.");

            if (string.IsNullOrEmpty(profile.Password))
                errors.Add("Password is required.");

            foreach (var other in others)
                if (string.Equals(other.ServerUrl, profile.ServerUrl, StringComparison.OrdinalIgnoreCase)
                    && other.Username == profile.Username)
                    errors.Add($"This account is already used by profile \"{other.Name}\".");

            return errors;
        }
    }
}
