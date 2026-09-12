import { useState } from 'react'
import { styled } from 'baseui'
import { Button, SIZE, KIND } from 'baseui/button'
import { StatefulInput } from 'baseui/input'
import { useThemeContext } from './Providers'

//background wrapper with theme
const FullScreenLayout = styled('div', ({ $theme }) => ({
    height: '100vh',
    width: '100vw',
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: $theme.colors.backgroundPrimary,
    margin: 0,
    boxSizing: 'border-box',
}))

//Center content
const Container = styled('div', {
    padding: '40px',
    width: '100%',
    maxWidth: '400px',
    display: 'flex',
    flexDirection: 'column',
    gap: '20px',
    fontFamily: 'sans-serif',
})

//Dynamic tile
const Title = styled('h1', ({ $theme }) => ({
    fontSize: '24px',
    margin: 0,
    color: $theme.colors.contentPrimary,
}))

export default function App() {
    const [clickCount, setClickCount] = useState<number>(0)
    const { currentTheme, toggleTheme } = useThemeContext()

    return (
        <FullScreenLayout>
            <Container>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <Title>Base Web Test</Title>

                    {/* Global toggle functions */}
                    <Button
                        size={SIZE.compact}
                        kind={KIND.secondary}
                        onClick={toggleTheme}
                    >
                        Switch to {currentTheme === 'light' ? 'Dark' : 'Light'}
                    </Button>
                </div>

                <StatefulInput
                    placeholder="Type something here..."
                    size={SIZE.default}
                />

                <Button
                    kind={KIND.primary}
                    size={SIZE.default}
                    onClick={() => setClickCount(prev => prev + 1)}
                >
                    Clicked {clickCount} times
                </Button>
            </Container>
        </FullScreenLayout>
    )
}
