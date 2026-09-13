import {
    HeaderNavigation,
    type HeaderNavigationProps,
    ALIGN,
    StyledNavigationList,
    StyledNavigationItem,
} from 'baseui/header-navigation';
import {styled, withStyle} from 'baseui';
import { StyledLink } from 'baseui/link';
import * as React from "react";
import ThemeToggle from "./ThemeToggle.tsx";
import GetStartedButton from "./GetStartedButton.tsx";

const TypedHeaderNavigation = (HeaderNavigation as unknown) as React.FC<React.PropsWithChildren<HeaderNavigationProps>>;

//brand typography and styling
const BrandText = styled('span', ({ $theme }) => ({
    ...$theme.typography.HeadingXXLarge,
    color: $theme.colors.contentPrimary,
    fontFamily: 'BrandFont, sans-serif',
    fontWeight: 700,
    cursor: 'pointer',

}));

//hover event styling for navigation buttons
const NavLink = withStyle(StyledLink, ({ $theme }) => ({
    ...$theme.typography.HeadingSmall,
    position: 'relative',
    textDecoration: 'none',
    color: $theme.colors.contentSecondary,
    transition: `color ${$theme.animation.timing500} ${$theme.animation.easeInOutQuinticCurve}`,

    ':after': {
        content: '""',
        position: 'absolute',
        bottom: '0px',
        left: '5%',
        width: '90%',
        height: '2.5px',
        borderRadius: '1.25px',
        backgroundColor: $theme.colors.contentPrimary,


        transformOrigin: 'center',
        transform: 'scaleX(0) translateZ(0)',
        backfaceVisibility: 'hidden',
        transition: `transform ${$theme.animation.timing500} ${$theme.animation.easeInOutQuinticCurve}`,
    },

    ':hover': {
        color: $theme.colors.contentPrimary,
        ':after': {
            transform: 'scaleX(1) translateZ(0)',
            fontWeight: 7000
        },
    },
}));

function Navbar() {
    return (
        <TypedHeaderNavigation
            overrides={{
                Root: {
                    style: ({$theme}) => ({
                        borderRadius: '100px',
                        border: $theme.borders.border300,
                        borderColor: $theme.colors.borderTransparent,
                        width: '100%',
                        boxSizing: 'border-box',
                        backgroundColor: $theme.colors.backgroundPrimary,
                        backdropFilter: 'blur',
                        display: "flex",
                        flexDirection: 'row',

                        padding: $theme.sizing.scale0,
                        justifyContent: "center",
                    }),
                }
            }}
        >
            {/* brand logo */}
            <StyledNavigationList $align={ALIGN.left}>
                <StyledNavigationItem>
                    <BrandText>Synapse</BrandText>
                </StyledNavigationItem>
            </StyledNavigationList>

            {/* navigation buttons */}
            <StyledNavigationList $align={ALIGN.center}>
                <StyledNavigationItem></StyledNavigationItem>
                <StyledNavigationItem>
                    <NavLink href={'#about'}> About </NavLink>
                </StyledNavigationItem>
                <StyledNavigationItem>
                    <NavLink href={"#features"}> Features </NavLink>
                </StyledNavigationItem>
                <StyledNavigationItem>
                    <NavLink href={"#pricing"}> Pricing </NavLink>
                </StyledNavigationItem>
            </StyledNavigationList>


            {/* action buttons */}
            <StyledNavigationList $align={ALIGN.right}>
                <StyledNavigationItem
                    style={{
                        display: 'flex',
                        alignItems: 'center',
                    }}>
                    <ThemeToggle/>
                    <GetStartedButton />
                </StyledNavigationItem>


            </StyledNavigationList>
        </TypedHeaderNavigation>
    );
}

export default Navbar