// Fake portal for the ChannelSource test. Shape pinned in plan Task 1 from tatatv.cc (Stalker portal 5.6.10), 2026-09-25:
//   stb.player.channels          ← itv/get_all_channels result.data (xpcom.common.js load_channels); 16.519 items there
//   module.tv.genres             ← itv/get_genres (tv.js); first entry is the pseudo-genre {id:'*', title:'All'}
//   stb.player.fav_channels_ids  ← itv/get_fav_ids (string ids)
//   module.tv._play_now(item)    ← what the TV list calls on OK (handles create_link errors, then stb.player.play)
// Only the structure is real; values are made up.
module.exports.SOURCE = {
  channels: 'stb.player.channels',
  genres: 'module.tv.genres',
  favIds: 'stb.player.fav_channels_ids',
  play: 'module.tv._play_now',
  fields: { id: 'id', number: 'number', name: 'name', genreId: 'tv_genre_id' },
  genreFields: { id: 'id', title: 'title' },
};

module.exports.makePortal = function makePortal() {
  const played = [];
  const tv = {
    genres: [{ id: '*', title: 'All' }, { id: 'g1', title: 'News' }, { id: 'g2', title: 'Sports' }],
    _play_now(item) { if (this !== tv) throw new Error('called without its owner'); played.push(item); },
  };
  return {
    stb: {
      player: {
        channels: [
          { id: '1', number: '1', name: 'ESPN HD', tv_genre_id: 'g2', cmd: 'ffrt http://x/1' },
          { id: '12', number: '12', name: 'Thời sự 24/7', tv_genre_id: 'g1', cmd: 'ffrt http://x/12' },
          { id: '13', number: '13', name: 'Đà Nẵng TV', tv_genre_id: 'g1', cmd: 'ffrt http://x/13' },
        ],
        fav_channels_ids: ['13'],
      },
    },
    module: { tv },
    __played: played,
  };
};
