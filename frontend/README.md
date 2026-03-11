# Radio Paradise Tracker - Vue.js Frontend

Beautiful, modern Vue.js 3 dashboard for tracking songs across all Radio Paradise channels.

## Features

✨ **Live Channel Monitoring**
- Real-time display of currently playing songs on all 3 channels (Main Mix, Rock Mix, Mellow Mix)
- Beautiful album cover artwork display
- Live status indicators with pulsing animations

📊 **Analytics & Visualizations**
- Interactive doughnut chart showing song distribution by channel
- Horizontal bar chart displaying most played tracks
- Statistics cards for quick insights

🎵 **Song Management**
- Comprehensive song list with filtering by channel
- Tab navigation for easy browsing (All Songs, Recent, by Channel)
- Play count tracking and timestamps
- Album cover thumbnails

🎨 **Modern Design**
- Glassmorphism UI with frosted-glass effects
- Smooth animations and transitions
- Responsive design for all screen sizes
- Custom scrollbar styling
- Gradient backgrounds

## Tech Stack

- **Vue 3** - Progressive JavaScript framework
- **Vite** - Next-generation frontend tooling
- **Chart.js** - Beautiful, responsive charts
- **Axios** - HTTP client for API calls
- **Inter Font** - Modern typography

## Getting Started

### Prerequisites
- Node.js (v18 or higher)
- Backend API running on port 8081

### Installation

```bash
# Navigate to frontend directory
cd frontend

# Install dependencies
npm install

# Start development server
npm run dev
```

The application will be available at **http://localhost:3000**

### Build for Production

```bash
npm run build
```

## Project Structure

```
frontend/
├── src/
│   ├── components/
│   │   ├── CurrentSong.vue       # Now playing card component
│   │   ├── StatsCard.vue         # Statistics display
│   │   ├── ChannelChart.vue      # Doughnut chart for channels
│   │   ├── MostPlayedChart.vue   # Bar chart for top songs
│   │   └── SongList.vue          # Filterable song list
│   ├── services/
│   │   └── api.js                # API service layer
│   ├── App.vue                   # Main application component
│   ├── main.js                   # Application entry point
│   └── style.css                 # Global styles
├── index.html                    # HTML entry point
├── vite.config.js               # Vite configuration
└── package.json                  # Dependencies

```

## API Integration

The frontend communicates with the Spring Boot backend via proxy:

- `/api/songs` - Get all tracked songs
- `/api/songs/channel/{id}` - Get songs by channel
- `/api/songs/recent` - Get recently played songs
- `/api/songs/most-played` - Get most played songs

Proxy configuration in `vite.config.js` forwards `/api` requests to `http://localhost:8081`.

## Features Showcase

### Now Playing Cards
Each channel displays:
- Channel name with color-coded badge
- Live indicator with pulsing animation
- Album cover artwork (with fallback)
- Song title, artist, and album
- Play count and last played timestamp

### Charts
- **Tracks by Channel**: Doughnut chart with channel distribution
- **Most Played**: Top 10 songs ranked by play count

### Song List
- Filterable by channel or view all
- Album cover thumbnails
- Channel badges with distinct colors
- Play count indicators
- Responsive grid layout

## Customization

### Channel Colors
Modify in `src/services/api.js`:
```javascript
export const CHANNELS = {
  '0': { name: 'Main Mix', color: '#667eea' },
  '1': { name: 'Rock Mix', color: '#f093fb' },
  '2': { name: 'Mellow Mix', color: '#4facfe' }
}
```

### Styling
Global CSS variables in `src/style.css`:
```css
:root {
  --primary: #667eea;
  --secondary: #764ba2;
  --accent: #f093fb;
  --card-bg: rgba(255, 255, 255, 0.95);
}
```

## Auto-Refresh

The dashboard automatically refreshes data every 30 seconds to show the latest songs and statistics.

## Browser Compatibility

- Chrome/Edge (latest)
- Firefox (latest)
- Safari (latest)

Enjoy tracking your favorite Radio Paradise channels! 🎵✨
