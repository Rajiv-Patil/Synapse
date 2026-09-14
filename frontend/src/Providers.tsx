// frontend/src/Providers.tsx
import { useState, createContext, useContext, type ReactNode } from 'react'
import { Client as Styletron } from 'styletron-engine-monolithic'
import { Provider as StyletronProvider } from 'styletron-react'
import { LightTheme, DarkTheme, BaseProvider } from 'baseui'

const engine = new Styletron()

// Create a React Context to expose the theme state globally
interface ThemeContextType {
    currentTheme: 'light' | 'dark'
    toggleTheme: () => void
}

const ThemeContext = createContext<ThemeContextType | undefined>(undefined)

export default function Providers({ children }: { children: ReactNode }) {
    const [themeName, setThemeName] = useState<'light' | 'dark'>('light')

    const toggleTheme = () => {
        setThemeName((prev) => (prev === 'light' ? 'dark' : 'light'))
    }

    const activeTheme = themeName === 'light' ? LightTheme : DarkTheme

    return (
        <StyletronProvider value={engine}>
            <BaseProvider theme={activeTheme}>
                <ThemeContext.Provider value={{ currentTheme: themeName, toggleTheme }}>
                    {children}
                </ThemeContext.Provider>
            </BaseProvider>
        </StyletronProvider>
    )
}

// Custom hook so any child component can change the theme with 1 line of code
export function useThemeContext() {
    const context = useContext(ThemeContext)
    if (!context) throw new Error('useThemeContext must be used within a Providers wrapper')
    return context
}
